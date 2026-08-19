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
import java.util.Map;

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

        checkApplyOrder(c);
        checkRoundTrip(c);
        checkNbtDedup(c, database);
        checkWritePath();
        checkMaterialNames();
        checkRolledBackStyling();
        checkMachineActivity();
        checkRollbackSemantics();

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
        var nbtStore = new com.gle.core.db.NbtStore(false);
        BlockLogDao blocks = new BlockLogDao(db, queue, ids, nbtStore);
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
        blocks.insertEntityKill(9000L, "uuid-writer", "minecraft:overworld", 5, 64, 5, "minecraft:zombie", null);
        // Именованный моб: снимок отличается от обычной особи и обязан сохраниться.
        blocks.insertEntityKill(9001L, "uuid-writer", "minecraft:overworld", 6, 64, 5, "minecraft:creeper",
                "именованный-крипер".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Никаких sleep: stop() обязан сам дождаться дренажа очереди.
        queue.stop();

        Connection c = db.connection();
        check("write: все строки blocks записаны (ожидали " + (N + 2) + ", в БД " + count(c, "blocks") + ")",
                count(c, "blocks") == N + 2);
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
        // Обычный моб не должен занимать место в хранилище снимков, именованный — должен.
        // b.action = 3 обязателен: id справочников materials и entities независимы, и без
        // фильтра по действию JOIN на entities цепляет обычные строки блоков.
        check("write: у обычного моба снимок не сохранён",
                count(c, "blocks b JOIN entities e ON b.type = e.id WHERE b.action = 3 "
                        + "AND e.name = 'minecraft:zombie' AND b.nbt_id IS NULL") == 1);
        check("write: у именованного моба снимок сохранён",
                count(c, "blocks b JOIN entities e ON b.type = e.id WHERE b.action = 3 "
                        + "AND e.name = 'minecraft:creeper' AND b.nbt_id IS NOT NULL") == 1);
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
        check("lookup: откатанная строка приглушена (серая)",
                style.getColor() != null && style.getColor().equals(
                        net.minecraft.network.chat.TextColor.fromLegacyFormat(
                                net.minecraft.ChatFormatting.GRAY)));
        // Главная проверка: без неё стиль выше существует, но на экране не виден.
        check("lookup: в откатанной строке не осталось legacy-кодов, гасящих стиль",
                rolled.getString().indexOf('\u00a7') < 0);
        check("lookup: текст строки не потерян",
                rolled.getString().contains("Steve") && rolled.getString().contains("stone"));
    }

    /**
     * Учёт собственной работы машины. Именно на этой арифметике держится то, что игроку
     * не приписывается переплавка — и, что важнее, что игроку ПРИПИСЫВАЕТСЯ всё остальное,
     * включая изъятие из слотов топлива и входа (через них прятали предметы).
     */
    private static void checkMachineActivity() {
        var pos = new net.minecraft.core.BlockPos(10, 64, -3);
        var other = new net.minecraft.core.BlockPos(11, 64, -3);

        check("machine: без старта учёт не ведётся", !com.gle.core.MachineActivity.isTracked(pos));
        com.gle.core.MachineActivity.start(pos);
        check("machine: после старта учёт ведётся", com.gle.core.MachineActivity.isTracked(pos));
        check("machine: соседняя позиция не отслеживается", !com.gle.core.MachineActivity.isTracked(other));

        // Тик 1: сожгла уголь. Тик 2: превратила руду в слиток.
        com.gle.core.MachineActivity.record(pos,
                Map.of("coal", 8, "raw_iron", 3),
                Map.of("coal", 7, "raw_iron", 3));
        com.gle.core.MachineActivity.record(pos,
                Map.of("coal", 7, "raw_iron", 3),
                Map.of("coal", 7, "raw_iron", 2, "iron_ingot", 1));

        var acc = com.gle.core.MachineActivity.stop(pos);
        check("machine: расход топлива учтён (coal=-1, получено " + acc.get("coal") + ")",
                Integer.valueOf(-1).equals(acc.get("coal")));
        check("machine: расход сырья учтён (raw_iron=-1)",
                Integer.valueOf(-1).equals(acc.get("raw_iron")));
        check("machine: произведённое учтено (iron_ingot=+1)",
                Integer.valueOf(1).equals(acc.get("iron_ingot")));
        check("machine: stop снимает учёт", !com.gle.core.MachineActivity.isTracked(pos));

        // Ключевой сценарий дыры: игрок положил алмаз в слот топлива и позже забрал.
        // Машина к алмазу непричастна, значит вычитать нечего и оба действия обязаны логироваться.
        com.gle.core.MachineActivity.start(pos);
        com.gle.core.MachineActivity.record(pos, Map.of("coal", 5), Map.of("coal", 4));
        var acc2 = com.gle.core.MachineActivity.stop(pos);
        check("machine: предмет, которого машина не касалась, не вычитается",
                acc2.get("diamond") == null);

        check("machine: stop без start возвращает пустое", com.gle.core.MachineActivity.stop(other).isEmpty());
    }

    /**
     * Откат возвращает территорию к снимку на момент timeFrom. Значит итог позиции задаёт
     * САМАЯ СТАРАЯ запись окна: обратные операции применяются от новых к старым, и она
     * применяется последней. Превью обязано показывать тот же итог, иначе оно врёт.
     */
    private static void checkRollbackSemantics() {
        // Сценарий из жизни: 12 минут назад сломали камень, 7 минут назад поставили землю.
        // Полный откат окна обязан вернуть КАМЕНЬ, а не воздух и не землю.
        var newestFirst = List.of(
                change(700L, "dirt", 1, 5, 64, 5),    // PLACE, новее
                change(300L, "stone", 0, 5, 64, 5));  // BREAK, старее
        var finals = com.gle.core.rollback.RollbackData.finalChangePerPosition(newestFirst);

        check("rollback: на позицию остаётся одна итоговая запись", finals.size() == 1);
        var decisive = finals.values().iterator().next();
        check("rollback: итог задаёт самая старая запись (ожидали stone/BREAK, получили "
                + decisive.material() + "/" + decisive.action() + ")",
                "stone".equals(decisive.material()) && decisive.action() == 0);

        // Разные позиции не должны схлопываться.
        var two = com.gle.core.rollback.RollbackData.finalChangePerPosition(List.of(
                change(700L, "dirt", 1, 5, 64, 5),
                change(600L, "sand", 1, 6, 64, 5)));
        check("rollback: разные позиции учитываются раздельно", two.size() == 2);
    }

    private static com.gle.core.rollback.RollbackData.BlockChange change(
            long time, String material, int action, int x, int y, int z) {
        return new com.gle.core.rollback.RollbackData.BlockChange(
                time, time, material, action, x, y, z, null, null, null);
    }

    /**
     * Порядок применения зависит от направления. Откат отменяет действия от новых к старым,
     * поэтому последней применяется самая старая запись и мир приходит к состоянию на timeFrom.
     * Restore — обратная операция: он проигрывает те же действия ЗАНОВО, значит идти надо
     * от старых к новым, иначе старое действие применится последним и затрёт более новое.
     * <p>
     * Данные заводятся в стороне от бокса остальных проверок, чтобы их не задеть.
     */
    private static void checkApplyOrder(Connection c) throws Exception {
        String b = "INSERT INTO blocks(id, time, user, level, x, y, z, type, action, rolled_back) "
                + "VALUES(?, ?, (SELECT id FROM users WHERE uuid='uuid-steve'), 1, 100, 64, 100, 1, 0, ?)";
        try (PreparedStatement ps = c.prepareStatement(b)) {
            ps.setLong(1, 10); ps.setLong(2, 5000); ps.setInt(3, 0); ps.executeUpdate();
            ps.setLong(1, 11); ps.setLong(2, 6000); ps.setInt(3, 0); ps.executeUpdate();
            ps.setLong(1, 12); ps.setLong(2, 5000); ps.setInt(3, 1); ps.executeUpdate();
            ps.setLong(1, 13); ps.setLong(2, 6000); ps.setInt(3, 1); ps.executeUpdate();
        }
        RollbackFilter f = new RollbackFilter();
        f.levelName = "minecraft:overworld";
        f.timeFrom = 0; f.timeTo = Long.MAX_VALUE;
        f.setBox(100, 64, 100, 2);

        var back = RollbackData.queryBlocks(c, f, true);
        check("order: откат идёт от новых к старым (получено " + back.size() + " записей)",
                back.size() == 2 && back.get(0).time() > back.get(1).time());

        var fwd = RollbackData.queryBlocks(c, f, false);
        check("order: restore проигрывает от старых к новым (получено " + fwd.size() + " записей)",
                fwd.size() == 2 && fwd.get(0).time() < fwd.get(1).time());

        check("order: откат и restore берут разные наборы строк",
                back.get(0).id() != fwd.get(0).id());
    }

    /**
     * Restore — обратная операция к откату, значит наборы строк обязаны сходиться:
     * что откат пометил применённым, то restore и должен взять, ничего сверх и ничего меньше.
     */
    private static void checkRoundTrip(Connection c) throws Exception {
        String ins = "INSERT INTO containers(id, time, user, level, x, y, z, type, amount, action, rolled_back) "
                + "VALUES(?, ?, (SELECT id FROM users WHERE uuid='uuid-steve'), 1, 200, 64, 200, 1, 4, ?, 0)";
        try (PreparedStatement ps = c.prepareStatement(ins)) {
            ps.setLong(1, 40); ps.setLong(2, 1000); ps.setInt(3, 1); ps.executeUpdate(); // ADD
            ps.setLong(1, 41); ps.setLong(2, 2000); ps.setInt(3, 0); ps.executeUpdate(); // REMOVE
        }
        RollbackFilter f = new RollbackFilter();
        f.levelName = "minecraft:overworld";
        f.timeFrom = 0; f.timeTo = Long.MAX_VALUE;
        f.setBox(200, 64, 200, 2);

        var toRollback = RollbackData.queryItems(c, f, true);
        check("roundtrip: откат видит обе новые строки", toRollback.size() == 2);
        check("roundtrip: до отката restore не видит ничего",
                RollbackData.queryItems(c, f, false).isEmpty());

        // Откат применил обе — помечаем ровно их.
        var applied = new java.util.ArrayList<Long>();
        for (var ch : toRollback) applied.add(ch.id());
        RollbackData.markRolledBack(c, "containers", applied, 1);

        var toRestore = RollbackData.queryItems(c, f, false);
        check("roundtrip: restore берёт ровно то, что пометил откат (" + toRestore.size() + " из 2)",
                toRestore.size() == 2);
        var restoreIds = new java.util.HashSet<Long>();
        for (var ch : toRestore) restoreIds.add(ch.id());
        check("roundtrip: наборы строк совпадают", restoreIds.containsAll(applied));
        check("roundtrip: после отката повторный откат ничего не берёт",
                RollbackData.queryItems(c, f, true).isEmpty());

        // Restore снимает пометку — цикл замыкается.
        RollbackData.markRolledBack(c, "containers", applied, 0);
        check("roundtrip: после restore откат снова видит строки",
                RollbackData.queryItems(c, f, true).size() == 2);
    }

    /**
     * NBT — самая тяжёлая часть лога, поэтому одинаковые снимки обязаны лежать в БД ОДИН раз,
     * а пустые не заводить строку вовсе. Без этого поток убийств мобов раздул бы базу кратно.
     */
    private static void checkNbtDedup(Connection c, GLDatabase database) throws Exception {
        var store = new com.gle.core.db.NbtStore(false);
        byte[] a = "снимок-обычного-зомби".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = "снимок-именованного".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        check("nbt: пустой снимок не хранится", store.idFor(c, null) == null);
        check("nbt: снимок нулевой длины не хранится", store.idFor(c, new byte[0]) == null);

        Integer id1 = store.idFor(c, a);
        Integer id2 = store.idFor(c, a.clone());   // то же содержимое, другой объект
        Integer id3 = store.idFor(c, b);
        check("nbt: одинаковое содержимое даёт один id", id1 != null && id1.equals(id2));
        check("nbt: разное содержимое даёт разные id", id3 != null && !id3.equals(id1));
        check("nbt: в таблице ровно 2 строки на 3 сохранения (" + count(c, "gle_nbt") + ")",
                count(c, "gle_nbt") == 2);

        // Кэш сброшен — id обязан остаться прежним, иначе ссылки в логе разъедутся.
        store.clear();
        check("nbt: после сброса кэша id не меняется", id1.equals(store.idFor(c, a)));

        byte[] back = com.gle.core.db.NbtStore.load(c, id1);
        check("nbt: снимок читается обратно без искажений", java.util.Arrays.equals(a, back));
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
