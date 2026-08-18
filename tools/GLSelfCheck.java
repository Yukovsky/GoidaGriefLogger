import com.gle.core.db.BlockLogDao;
import com.gle.core.db.ContainerLogDao;
import com.gle.core.db.GLDatabase;
import com.gle.core.db.IdCache;
import com.gle.core.db.SchemaMigrator;
import com.gle.core.db.SessionDao;
import com.gle.core.db.StorageSettings;
import com.gle.core.db.WriteQueue;
import com.gle.core.rollback.RollbackData;
import com.gle.core.rollback.RollbackFilter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * Автономная самопроверка слоя выборки для отката: поднимает НАСТОЯЩУЮ схему через
 * {@link SchemaMigrator} в файловой SQLite и гоняет НАСТОЯЩИЕ запросы {@link RollbackData}.
 * <p>
 * Проверяет ровно то, что было сломано и что тихо ломается снова при правке SQL:
 * предикат {@code rolled_back} и порядок bind-параметров.
 * <p>
 * Запуск: см. tools/selfcheck.sh
 */
public final class GLSelfCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        java.io.File db = java.io.File.createTempFile("gle-selfcheck", ".sqlite");
        db.deleteOnExit();

        StorageSettings settings = new StorageSettings(
                false, "", 3306, "", "", "", 30, db.getAbsolutePath());
        GLDatabase database = new GLDatabase(settings);
        check("подключение к SQLite", database.connect());
        Connection c = database.connection();
        new SchemaMigrator(database).migrate();

        check("у blocks есть колонка id", hasColumn(c, "blocks", "id"));
        check("у containers есть колонка id", hasColumn(c, "containers", "id"));

        seed(c);

        RollbackFilter f = new RollbackFilter();
        f.levelName = "minecraft:overworld";
        f.timeFrom = 0;
        f.timeTo = Long.MAX_VALUE;
        f.setBox(0, 64, 0, 16);

        // reverse=true — откат берёт ТОЛЬКО ещё не откатанные строки.
        List<RollbackData.BlockChange> toRollback = RollbackData.queryBlocks(c, f, true);
        check("откат видит только rolled_back=0 (ожидали 1, получили " + toRollback.size() + ")",
                toRollback.size() == 1);
        check("откат взял именно нетронутую строку",
                !toRollback.isEmpty() && toRollback.get(0).id() == 1L);

        // reverse=false — restore берёт ТОЛЬКО уже откатанные.
        List<RollbackData.BlockChange> toRestore = RollbackData.queryBlocks(c, f, false);
        check("restore видит только rolled_back=1 (ожидали 1, получили " + toRestore.size() + ")",
                toRestore.size() == 1);
        check("restore взял именно откатанную строку",
                !toRestore.isEmpty() && toRestore.get(0).id() == 2L);

        List<RollbackData.ItemChange> items = RollbackData.queryItems(c, f, true);
        check("контейнеры: откат видит только rolled_back=0 (ожидали 1, получили " + items.size() + ")",
                items.size() == 1);

        // Bind-порядок не должен ломаться от добавления фильтров по игроку и материалу.
        f.playerNames.add("Steve");
        f.includeMaterials.add("stone");
        check("запрос с фильтрами игрока и материала исполняется",
                RollbackData.queryBlocks(c, f, true) != null);

        // Пометка по id меняет ровно одну строку и уводит её из выборки отката.
        f.playerNames.clear();
        f.includeMaterials.clear();
        check("пометка по id затронула 1 строку",
                RollbackData.markRolledBack(c, "blocks", List.of(1L), 1) == 1);
        check("помеченная строка исчезла из выборки отката",
                RollbackData.queryBlocks(c, f, true).isEmpty());

        checkWritePath();
        checkMaterialNames();
        checkRolledBackStyling();

        database.close();
        if (failures > 0) {
            System.out.println("\nПРОВАЛЕНО проверок: " + failures);
            System.exit(1);
        }
        System.out.println("\nВсе проверки пройдены.");
    }

    /**
     * Путь ЗАПИСИ: пакетная вставка через {@link WriteQueue} и гарантированный дренаж на остановке.
     * Проверяет ровно то, что ломается молча: строки, ушедшие в очередь, обязаны оказаться в БД,
     * в том числе те, что поставлены прямо перед {@code stop()}.
     */
    private static void checkWritePath() throws Exception {
        java.io.File f = java.io.File.createTempFile("gle-write", ".sqlite");
        f.deleteOnExit();
        GLDatabase db = new GLDatabase(new StorageSettings(false, "", 3306, "", "", "", 30, f.getAbsolutePath()));
        check("write: подключение", db.connect());
        new SchemaMigrator(db).migrate();

        WriteQueue queue = new WriteQueue(db, 5000);
        IdCache ids = new IdCache(false);
        queue.setOnRollback(ids::clear);
        BlockLogDao blocks = new BlockLogDao(db, queue, ids);
        ContainerLogDao containers = new ContainerLogDao(db, queue, ids);
        SessionDao sessions = new SessionDao(db, queue, ids);
        queue.start();

        final int N = 250;
        sessions.insert(new SessionDao.SessionEntry(
                1000L, "Writer", "uuid-writer", "minecraft:overworld", 1, 64, 1, 0));
        for (int i = 0; i < N; i++) {
            blocks.insert(new BlockLogDao.BlockEntry(
                    2000L + i, "uuid-writer", "minecraft:overworld", i, 64, 0,
                    "stone", 0, null, null, null, null, false));
            containers.insert(new ContainerLogDao.ContainerEntry(
                    2000L + i, "uuid-writer", "minecraft:overworld", i, 64, 0,
                    "stone", null, 1, 1));
        }
        // Убийство: type ссылается на entities, а не materials — отдельный SQL, отдельный пакет.
        blocks.insertEntityKill(9000L, "uuid-writer", "minecraft:overworld", 5, 64, 5, "minecraft:zombie");

        // Никаких sleep: stop() обязан сам дождаться дренажа очереди.
        queue.stop();

        Connection c = db.connection();
        check("write: все строки blocks записаны (ожидали " + (N + 1) + ", в БД " + count(c, "blocks") + ")",
                count(c, "blocks") == N + 1);
        check("write: все строки containers записаны (ожидали " + N + ", в БД " + count(c, "containers") + ")",
                count(c, "containers") == N);
        check("write: сессия записана", count(c, "sessions") == 1);
        check("write: история имён записана", count(c, "usernames") == 1);
        check("write: ни одна запись не отброшена (dropped=" + queue.droppedCount() + ")",
                queue.droppedCount() == 0);
        // P0-5: пользователя не было в users, но событие обязано было его завести, а не пропасть.
        check("write: пользователь заведён автоматически",
                count(c, "users WHERE uuid = 'uuid-writer'") == 1);
        check("write: имя пользователя починено входом (Writer, а не плейсхолдер)",
                count(c, "users WHERE uuid = 'uuid-writer' AND name = 'Writer'") == 1);
        // Строка убийства обязана ссылаться на entities, а не на materials.
        check("write: убийство записано с id из entities",
                count(c, "blocks b JOIN entities e ON b.type = e.id WHERE b.action = 3 "
                        + "AND e.name = 'minecraft:zombie'") == 1);
        db.close();
    }

    /** Нормализация имён: префикс minecraft: снимается только в начале, чужие id не калечатся. */
    private static void checkMaterialNames() {
        check("materials: ванильный префикс снят",
                "stone".equals(com.gle.core.GLMaterials.normalize(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "stone"))));
        check("materials: чужой namespace сохранён",
                "create:cogwheel".equals(com.gle.core.GLMaterials.normalize(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "cogwheel"))));
        // Раньше replace() вырезал подстроку в любой позиции и портил такой id.
        check("materials: id с подстрокой minecraft внутри не искажён",
                "notminecraft:foo".equals(com.gle.core.GLMaterials.normalize(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("notminecraft", "foo"))));
    }

    /**
     * Откатанная запись обязана отличаться визуально САМА ПО СЕБЕ, без наведения курсора.
     * Ловушка: legacy-код § внутри текста сбрасывает strikethrough и цвет компонента, поэтому
     * стиль виден только если кодов в тексте не осталось.
     */
    private static void checkRolledBackStyling() {
        String body = "\u00a77" + "5m \u00a7fSteve \u00a7cсломал \u00a7fstone \u00a77@ 1,2,3";

        var plain = com.gle.core.command.LookupService.bodyComponent(body, false);
        check("lookup: обычная строка сохраняет свои цвета",
                plain.getString().indexOf('\u00a7') >= 0);

        var rolled = com.gle.core.command.LookupService.bodyComponent(body, true);
        var style = rolled.getStyle();
        check("lookup: откатанная строка зачёркнута",
                Boolean.TRUE.equals(style.isStrikethrough()));
        check("lookup: откатанная строка затемнена",
                style.getColor() != null && style.getColor().equals(
                        net.minecraft.network.chat.TextColor.fromLegacyFormat(
                                net.minecraft.ChatFormatting.DARK_GRAY)));
        // Главная проверка: без неё стиль выше существует, но на экране не виден.
        check("lookup: в откатанной строке не осталось legacy-кодов, гасящих стиль",
                rolled.getString().indexOf('\u00a7') < 0);
        check("lookup: текст строки не потерян",
                rolled.getString().contains("Steve") && rolled.getString().contains("stone"));
    }

    private static int count(Connection c, String from) throws Exception {
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + from)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** Две строки blocks (одна откатана) и две containers (одна откатана). */
    private static void seed(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO levels(id, name) VALUES(1, 'minecraft:overworld')");
            st.execute("INSERT INTO materials(id, name) VALUES(1, 'stone')");
            // id не задаём: миграция уже завела 12 системных пользователей.
            st.execute("INSERT INTO users(name, uuid) VALUES('Steve', 'uuid-steve')");
        }
        String b = "INSERT INTO blocks(id, time, user, level, x, y, z, type, action, rolled_back) "
                + "VALUES(?, ?, (SELECT id FROM users WHERE uuid='uuid-steve'), 1, 0, 64, 0, 1, 0, ?)";
        try (PreparedStatement ps = c.prepareStatement(b)) {
            ps.setLong(1, 1); ps.setLong(2, 1000); ps.setInt(3, 0); ps.executeUpdate();
            ps.setLong(1, 2); ps.setLong(2, 2000); ps.setInt(3, 1); ps.executeUpdate();
        }
        String ct = "INSERT INTO containers(id, time, user, level, x, y, z, type, amount, action, rolled_back) "
                + "VALUES(?, ?, (SELECT id FROM users WHERE uuid='uuid-steve'), 1, 0, 64, 0, 1, 5, 1, ?)";
        try (PreparedStatement ps = c.prepareStatement(ct)) {
            ps.setLong(1, 1); ps.setLong(2, 1000); ps.setInt(3, 0); ps.executeUpdate();
            ps.setLong(1, 2); ps.setLong(2, 2000); ps.setInt(3, 1); ps.executeUpdate();
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws Exception {
        try (var rs = c.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  ПРОВАЛ ") + what);
        if (!ok) failures++;
    }
}
