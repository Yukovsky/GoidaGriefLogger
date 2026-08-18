package com.gle.core.command;

import com.gle.core.GLActions;
import com.gle.core.db.GLStorage;
import com.gle.core.rollback.RollbackFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lookup в стиле CoreProtect (собственная реализация GLE): читает ВСЕ источники GriefLogger —
 * blocks, containers, items (выбросы/подбор/крафт/съедание/выстрел), sessions — с фильтрами GLE
 * (принимает {@code :} и {@code []}). Откатанные записи §mзачёркнуты§r. Вывод интерактивный:
 * наведение — детали, клик — телепорт к координатам, постраничная навигация.
 * Запрос асинхронный; рендер/вывод — на главном потоке.
 */
public final class LookupService {

    private LookupService() {}

    private static final int FETCH = 200;     // сколько строк тянуть
    /** Имя объекта строки blocks: материал, а для строк убийства — имя сущности. */
    private static final String NAME_COL = "COALESCE(m.name, e.name)";
    private static final int PAGE_SIZE = 8;   // строк на страницу в чате

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GLE-lookup"); t.setDaemon(true); return t;
    });
    /** Последний результат на игрока (готовые строки) для постраничного вывода. */
    private static final Map<UUID, List<Component>> LAST = new ConcurrentHashMap<>();

    private record Row(long time, String user, String actionText, String material,
                       int x, int y, int z, boolean rolledBack, int amount, String source,
                       String category) {}

    public static void run(net.minecraft.server.MinecraftServer server, RollbackFilter f, ServerPlayer player) {
        if (!GLStorage.isReady()) { player.sendSystemMessage(Component.literal("§cХранилище недоступно.")); return; }
        EXEC.submit(() -> {
            try (Connection conn = GLStorage.get().database().newConnection()) {
                List<Row> rows = new ArrayList<>();
                // include:<материал> — белый список предметов/блоков: события без материала
                // (входы/выходы, смерти, таблички) скрываем, иначе они «протекают» мимо фильтра
                // и выглядят как «фильтр не сработал». При exclude: их оставляем (они не исключённый материал).
                boolean materialOnly = !f.includeMaterials.isEmpty();
                if (f.includeBlocks) queryBlocks(conn, f, rows);
                if (f.includeItems) { queryContainers(conn, f, rows); queryItems(conn, f, rows); }
                // Собственные таблицы GLE — иначе таблички/рамки/смерть не видны в lookup.
                queryFrames(conn, f, rows); // у рамок есть предмет — фильтр по материалу применяется
                if (!materialOnly) {
                    querySigns(conn, f, rows);
                    // sessions/deaths привязаны к позиции ИГРОКА, а не к объекту. В инспекции
                    // одной клетки они давали «вошёл/вышел» вместо истории блока.
                    if (f.includePlayerEvents) {
                        querySessions(conn, f, rows);
                        queryDeaths(conn, f, rows);
                    }
                }
                if (!f.actionsInclude.isEmpty() || !f.actionsExclude.isEmpty()) {
                    rows.removeIf(r -> !com.gle.core.rollback.ActionFilters.allows(f, r.category()));
                }
                rows.sort(Comparator.comparingLong(Row::time).reversed());
                long now = System.currentTimeMillis();
                List<Component> lines = new ArrayList<>();
                for (Row r : rows) lines.add(renderLine(r, now));
                server.execute(() -> {
                    LAST.put(player.getUUID(), lines);
                    showPage(player, 0);
                });
            } catch (Exception e) {
                server.execute(() -> player.sendSystemMessage(
                        Component.literal("§c" + com.gle.core.rollback.RollbackManager.translateDbError(e))));
            }
        });
    }

    /**
     * Инспектор ({@code /gl inspect}): история одного блока — полный диапазон времени, бокс в одну
     * клетку, текущий мир игрока. Переиспользует обычный {@link #run} со специально собранным фильтром.
     */
    public static void runAt(net.minecraft.server.MinecraftServer server, ServerPlayer player,
                             net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        RollbackFilter f = new RollbackFilter();
        f.timeFrom = 0L;
        f.timeTo = System.currentTimeMillis();
        f.levelName = level.dimension().location().toString();
        f.setBox(pos.getX(), pos.getY(), pos.getZ(), 0);
        f.includePlayerEvents = false; // история объекта, а не того, кто рядом логинился
        run(server, f, player);
    }

    /** Показать страницу сохранённого результата (вызывается из /gl page). */
    public static void showPage(ServerPlayer player, int page) {
        List<Component> lines = LAST.get(player.getUUID());
        if (lines == null || lines.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7[GLE] Нет результатов. Сначала /gl lookup <фильтры>."));
            return;
        }
        int totalPages = (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        player.sendSystemMessage(Component.literal("§3---- §bGLE Lookup §7(стр. " + (page + 1) + "/" + totalPages
                + ", всего " + lines.size() + ") §3----"));
        int start = page * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, lines.size()); i++) {
            player.sendSystemMessage(lines.get(i));
        }
        player.sendSystemMessage(nav(page, totalPages));
    }

    private static MutableComponent nav(int page, int totalPages) {
        MutableComponent prev = Component.literal(page > 0 ? "§b[◀ Назад]" : "§8[◀ Назад]");
        if (page > 0) prev = prev.withStyle(s -> s.withClickEvent(
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gl page " + page)));
        MutableComponent next = Component.literal(page + 1 < totalPages ? "§b[Вперёд ▶]" : "§8[Вперёд ▶]");
        if (page + 1 < totalPages) next = next.withStyle(s -> s.withClickEvent(
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gl page " + (page + 2))));
        return Component.literal("").append(prev).append(Component.literal("  ")).append(next);
    }

    private static MutableComponent renderLine(Row r, long now) {
        String mat = r.material() == null ? "?" : r.material();
        String body = ago(now - r.time()) + " §f" + safe(r.user()) + " " + r.actionText() + " §f" + mat
                + (r.amount() > 0 ? " §7x" + r.amount() : "")
                + " §7@ " + r.x() + "," + r.y() + "," + r.z();
        MutableComponent line = Component.literal(body);
        if (r.rolledBack()) line = Component.literal(body).withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.GRAY);

        String hover = "§7Время: §f" + java.time.Instant.ofEpochMilli(r.time())
                + "\n§7Игрок: §f" + safe(r.user())
                + "\n§7Действие: §f" + stripColors(r.actionText())
                + "\n§7Объект: §f" + mat + (r.amount() > 0 ? " x" + r.amount() : "")
                + "\n§7Координаты: §f" + r.x() + ", " + r.y() + ", " + r.z()
                + (r.source() != null ? "\n§7Источник: §f" + r.source() : "")
                + (r.rolledBack() ? "\n§cОткатано" : "")
                + "\n§8Клик — телепорт";
        final MutableComponent fLine = line;
        return fLine.withStyle(s -> s
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/tp " + r.x() + " " + (r.y() + 1) + " " + r.z())));
    }

    // --- запросы ---

    private static void queryBlocks(Connection conn, RollbackFilter f, List<Row> rows) throws SQLException {
        // Соглашение GriefLogger: у строк убийства (action=KILL_ENTITY) колонка blocks.type
        // ссылается на entities.id, а НЕ на materials.id (см. BlockLogDao.insertEntityKill).
        // Безусловный join на materials давал случайное имя предмета вместо имени моба.
        StringBuilder sql = new StringBuilder(
                "SELECT b.time, u.name, b.action, " + NAME_COL + ", b.x, b.y, b.z, b.rolled_back, b.source_type FROM blocks b "
                + "INNER JOIN levels l ON b.level=l.id LEFT JOIN users u ON b.user=u.id "
                + "LEFT JOIN materials m ON b.type=m.id AND b.action <> " + GLActions.KILL_ENTITY + " "
                + "LEFT JOIN entities e ON b.type=e.id AND b.action = " + GLActions.KILL_ENTITY + " "
                + "WHERE " + levelClause(f) + " AND b.time BETWEEN ? AND ? "
                + "AND b.x BETWEEN ? AND ? AND b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ?");
        playerClauses(sql, f, "b");
        materialSub(sql, f, NAME_COL);
        sql.append(" ORDER BY b.time DESC LIMIT ").append(FETCH);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindBox(ps, f);
            i = bindPlayers(ps, f, i);
            bindMaterials(ps, f, i);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int action = rs.getInt(3);
                    rows.add(new Row(rs.getLong(1), rs.getString(2), blockAction(action),
                            rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8) != 0, 0,
                            rs.getString(9), com.gle.core.rollback.ActionFilters.blockCategory(action)));
                }
            }
        }
    }

    private static void queryContainers(Connection conn, RollbackFilter f, List<Row> rows) throws SQLException {
        itemLike(conn, f, rows, "containers", true);
    }

    private static void queryItems(Connection conn, RollbackFilter f, List<Row> rows) throws SQLException {
        itemLike(conn, f, rows, "items", false);
    }

    /** containers и items имеют одинаковую схему; rolled_back есть только у containers. */
    private static void itemLike(Connection conn, RollbackFilter f, List<Row> rows, String table, boolean hasRolled) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT t.time, u.name, t.action, m.name, t.x, t.y, t.z, t.amount"
                + (hasRolled ? ", t.rolled_back" : "") + " FROM " + table + " t "
                + "INNER JOIN levels l ON t.level=l.id LEFT JOIN users u ON t.user=u.id "
                + "LEFT JOIN materials m ON t.type=m.id WHERE " + levelClause(f) + " AND t.time BETWEEN ? AND ? "
                + "AND t.x BETWEEN ? AND ? AND t.y BETWEEN ? AND ? AND t.z BETWEEN ? AND ?");
        playerClauses(sql, f, "t");
        materialSub(sql, f);
        sql.append(" ORDER BY t.time DESC LIMIT ").append(FETCH);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindBox(ps, f);
            i = bindPlayers(ps, f, i);
            bindMaterials(ps, f, i);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean rolled = hasRolled && rs.getInt(9) != 0;
                    rows.add(new Row(rs.getLong(1), rs.getString(2), itemAction(rs.getInt(3)), rs.getString(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7), rolled, rs.getInt(8), null,
                            com.gle.core.rollback.ActionFilters.CONTAINER));
                }
            }
        }
    }

    private static void querySessions(Connection conn, RollbackFilter f, List<Row> rows) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT s.time, u.name, s.action, s.x, s.y, s.z FROM sessions s "
                + "INNER JOIN levels l ON s.level=l.id LEFT JOIN users u ON s.user=u.id "
                + "WHERE " + levelClause(f) + " AND s.time BETWEEN ? AND ? "
                + "AND s.x BETWEEN ? AND ? AND s.y BETWEEN ? AND ? AND s.z BETWEEN ? AND ?");
        playerClauses(sql, f, "s");
        sql.append(" ORDER BY s.time DESC LIMIT ").append(FETCH);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindBox(ps, f);
            bindPlayers(ps, f, i);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Row(rs.getLong(1), rs.getString(2),
                        rs.getInt(3) == 0 ? "§aвошёл" : "§cвышел", "", rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        false, 0, null, com.gle.core.rollback.ActionFilters.SESSION));
            }
        }
    }

    /** Изменения текста табличек (gle_signs). */
    private static void querySigns(Connection conn, RollbackFilter f, List<Row> rows) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT s.time, u.name, s.front_after, s.x, s.y, s.z FROM gle_signs s "
                + "INNER JOIN levels l ON s.level=l.id LEFT JOIN users u ON s.user=u.id "
                + "WHERE " + levelClause(f) + " AND s.time BETWEEN ? AND ? "
                + "AND s.x BETWEEN ? AND ? AND s.y BETWEEN ? AND ? AND s.z BETWEEN ? AND ?");
        playerClauses(sql, f, "s");
        sql.append(" ORDER BY s.time DESC LIMIT ").append(FETCH);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindBox(ps, f);
            bindPlayers(ps, f, i);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String firstLine = firstNonEmptyLine(rs.getString(3));
                    rows.add(new Row(rs.getLong(1), rs.getString(2), "§eтабличка", firstLine,
                            rs.getInt(4), rs.getInt(5), rs.getInt(6), false, 0, "sign",
                            com.gle.core.rollback.ActionFilters.SIGN));
                }
            }
        }
    }

    /** Вставка/поворот предмета в рамке (gle_world_entities). */
    private static void queryFrames(Connection conn, RollbackFilter f, List<Row> rows) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT w.time, u.name, w.action, w.item, w.x, w.y, w.z, w.source_type FROM gle_world_entities w "
                + "INNER JOIN levels l ON w.level=l.id LEFT JOIN users u ON w.user=u.id "
                + "WHERE " + levelClause(f) + " AND w.time BETWEEN ? AND ? "
                + "AND w.x BETWEEN ? AND ? AND w.y BETWEEN ? AND ? AND w.z BETWEEN ? AND ?");
        playerClauses(sql, f, "w");
        sql.append(com.gle.core.rollback.MaterialMatcher.whereFragment("w.item", f.includeMaterials, f.excludeMaterials));
        sql.append(" ORDER BY w.time DESC LIMIT ").append(FETCH);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindBox(ps, f);
            i = bindPlayers(ps, f, i);
            bindMaterials(ps, f, i);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), frameAction(rs.getString(3)), rs.getString(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7), false, 0, rs.getString(8),
                            com.gle.core.rollback.ActionFilters.FRAME));
                }
            }
        }
    }

    /** Смерти игроков (gle_player_deaths). Игрок резолвится по player_uuid. */
    private static void queryDeaths(Connection conn, RollbackFilter f, List<Row> rows) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT d.time, u.name, d.cause, d.x, d.y, d.z FROM gle_player_deaths d "
                + "INNER JOIN levels l ON d.level=l.id LEFT JOIN users u ON u.uuid=d.player_uuid "
                + "WHERE " + levelClause(f) + " AND d.time BETWEEN ? AND ? "
                + "AND d.x BETWEEN ? AND ? AND d.y BETWEEN ? AND ? AND d.z BETWEEN ? AND ?");
        if (!f.playerNames.isEmpty())
            sql.append(" AND u.name IN (").append(placeholders(f.playerNames.size())).append(")");
        if (!f.excludePlayerNames.isEmpty())
            sql.append(" AND (u.name IS NULL OR u.name NOT IN (").append(placeholders(f.excludePlayerNames.size())).append("))");
        sql.append(" ORDER BY d.time DESC LIMIT ").append(FETCH);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindBox(ps, f);
            for (String name : f.playerNames) ps.setString(i++, name);
            for (String name : f.excludePlayerNames) ps.setString(i++, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), "§4умер", rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6), false, 0, "death",
                            com.gle.core.rollback.ActionFilters.DEATH));
                }
            }
        }
    }

    private static String frameAction(String a) {
        if ("INSERT_ITEM".equals(a)) return "§aвставил в рамку";
        if ("ROTATE".equals(a)) return "§eповернул в рамке";
        return "§eрамка";
    }

    private static String firstNonEmptyLine(String s) {
        if (s == null) return "табличка";
        for (String line : s.split("\n")) {
            if (!line.isBlank()) return line.trim();
        }
        return "(пусто)";
    }

    // --- тексты действий ---

    private static String blockAction(int a) {
        return switch (a) {
            case 0 -> "§cсломал"; case 1 -> "§aпоставил"; case 2 -> "§eиспользовал"; case 3 -> "§cубил";
            default -> "§7?";
        };
    }

    private static String itemAction(int a) {
        return switch (a) {
            case 0 -> "§c-извлёк"; case 1 -> "§a+положил"; case 2 -> "§cвыбросил"; case 3 -> "§aподобрал";
            case 4 -> "§aскрафтил"; case 5 -> "§cсломал предмет"; case 6 -> "§eсъел";
            case 7 -> "§eметнул"; case 8 -> "§eвыстрелил"; case 9 -> "§a+эндер"; case 10 -> "§c-эндер";
            default -> "§7?";
        };
    }

    private static String safe(String s) { return s == null ? "?" : s; }
    private static String stripColors(String s) { return s.replaceAll("§.", ""); }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return "§7" + s + "s";
        long m = s / 60; if (m < 60) return "§7" + m + "m";
        long h = m / 60; if (h < 24) return "§7" + h + "h";
        return "§7" + (h / 24) + "d";
    }

    private static void materialSub(StringBuilder sql, RollbackFilter f) {
        materialSub(sql, f, "m.name");
    }

    /** Фильтр по материалу на произвольном выражении имени (для blocks — COALESCE материал/сущность). */
    private static void materialSub(StringBuilder sql, RollbackFilter f, String col) {
        sql.append(com.gle.core.rollback.MaterialMatcher.whereFragment(col, f.includeMaterials, f.excludeMaterials));
    }

    /** WHERE-фрагмент по миру: либо равенство имени уровня (с биндом), либо «всё» без бинда. */
    private static String levelClause(RollbackFilter f) {
        return f.allWorlds ? "1=1" : "l.name=?";
    }

    private static int bindBox(PreparedStatement ps, RollbackFilter f) throws SQLException {
        int i = 1;
        if (!f.allWorlds) ps.setString(i++, f.levelName);
        ps.setLong(i++, f.timeFrom); ps.setLong(i++, f.timeTo);
        ps.setInt(i++, f.minX); ps.setInt(i++, f.maxX);
        ps.setInt(i++, f.minY); ps.setInt(i++, f.maxY);
        ps.setInt(i++, f.minZ); ps.setInt(i++, f.maxZ);
        return i;
    }

    private static int bindMaterials(PreparedStatement ps, RollbackFilter f, int i) throws SQLException {
        return com.gle.core.rollback.MaterialMatcher.bind(ps,
                com.gle.core.rollback.MaterialMatcher.params(f.includeMaterials, f.excludeMaterials), i);
    }

    /** WHERE-фрагменты «по игроку»: include (user:) и exclude (u:!) — для таблиц с колонкой user. */
    private static void playerClauses(StringBuilder sql, RollbackFilter f, String alias) {
        if (!f.playerNames.isEmpty())
            sql.append(" AND ").append(alias).append(".user IN (SELECT id FROM users WHERE name IN (")
               .append(placeholders(f.playerNames.size())).append("))");
        if (!f.excludePlayerNames.isEmpty())
            sql.append(" AND ").append(alias).append(".user NOT IN (SELECT id FROM users WHERE name IN (")
               .append(placeholders(f.excludePlayerNames.size())).append("))");
    }

    private static int bindPlayers(PreparedStatement ps, RollbackFilter f, int i) throws SQLException {
        for (String name : f.playerNames) ps.setString(i++, name);
        for (String name : f.excludePlayerNames) ps.setString(i++, name);
        return i;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
