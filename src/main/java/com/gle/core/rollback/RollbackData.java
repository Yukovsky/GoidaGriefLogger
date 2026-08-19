package com.gle.core.rollback;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Чтение изменений блоков/предметов из БД GriefLogger для роллбека.
 * Выборка в ОБРАТНОМ хронологическом порядке (от новых к старым) — применяя обратные
 * операции в этом порядке, мы реконструируем состояние мира на момент {@code timeFrom}.
 */
public final class RollbackData {

    private RollbackData() {}

    /** Изменение блока для реверса. */
    public record BlockChange(
            long id, long time, String material, int action,
            int x, int y, int z,
            @Nullable String sourceType, @Nullable String extraData, byte @Nullable [] nbt
    ) {}

    /** Изменение содержимого контейнера для реверса. */
    public record ItemChange(
            long id, long time, String material, int action,
            int x, int y, int z,
            byte @Nullable [] data, int amount
    ) {}

    /**
     * @param reverse true = откат (берём ещё НЕ откатанные строки), false = restore (берём откатанные).
     *                Без этого предиката повторный откат переигрывал те же дельты, а {@code /gl restore}
     *                по неоткатанному окну дублировал предметы.
     */
    public static List<BlockChange> queryBlocks(Connection conn, RollbackFilter f, boolean reverse) throws SQLException {
        List<Integer> actions = ActionFilters.allowedRollbackBlockActions(f);
        if (actions.isEmpty()) return new ArrayList<>();  // фильтр действий исключил и сломы, и постановки
        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.time, m.name, b.action, b.x, b.y, b.z, b.source_type, b.extra_data, b.block_nbt
                FROM blocks b
                INNER JOIN levels l ON b.level = l.id
                LEFT JOIN materials m ON b.type = m.id
                WHERE l.name = ? AND b.time BETWEEN ? AND ?
                  AND b.x BETWEEN ? AND ? AND b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ?
                  AND COALESCE(b.rolled_back, 0) = ?
                """);
        sql.append("  AND b.action IN (").append(intList(actions)).append(")\n");
        appendFilters(sql, f, "b");
        // Порядок зависит от направления. Откат отменяет действия от новых к старым, поэтому
        // последней применяется самая старая запись и мир приходит к состоянию на timeFrom.
        // Restore — обратная операция: он ПРОИГРЫВАЕТ те же действия заново, значит идти надо
        // от старых к новым, иначе старое действие применится последним и затрёт более новое.
        sql.append(reverse ? " ORDER BY b.time DESC" : " ORDER BY b.time ASC");

        List<BlockChange> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindAll(ps, f, reverse ? 0 : 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BlockChange(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7),
                            rs.getString(8), rs.getString(9), rs.getBytes(10)));
                }
            }
        }
        return enrichNbt(conn, f, out);
    }

    /**
     * Доливает NBT для сломов (action=0) с пустым block_nbt — из gle_block_nbt (сломы контейнеров
     * игроком логирует GriefLogger без NBT; снимок мы храним отдельно). Без этого откат вернул бы
     * контейнер пустым.
     */
    private static List<BlockChange> enrichNbt(Connection conn, RollbackFilter f, List<BlockChange> list) throws SQLException {
        boolean anyNull = false;
        for (BlockChange c : list) if (c.action() == 0 && c.nbt() == null) { anyNull = true; break; }
        if (!anyNull) return list;

        Map<Long, TreeMap<Long, byte[]>> byPos = new HashMap<>();
        String sql = "SELECT x, y, z, time, nbt FROM gle_block_nbt WHERE level = ? "
                + "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND time BETWEEN ? AND ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.levelName);
            ps.setInt(2, f.minX); ps.setInt(3, f.maxX);
            ps.setInt(4, f.minY); ps.setInt(5, f.maxY);
            ps.setInt(6, f.minZ); ps.setInt(7, f.maxZ);
            ps.setLong(8, f.timeFrom - 5000L); ps.setLong(9, f.timeTo + 5000L);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long key = new BlockPos(rs.getInt(1), rs.getInt(2), rs.getInt(3)).asLong();
                    byPos.computeIfAbsent(key, k -> new TreeMap<>()).put(rs.getLong(4), rs.getBytes(5));
                }
            }
        }
        if (byPos.isEmpty()) return list;

        List<BlockChange> result = new ArrayList<>(list.size());
        for (BlockChange c : list) {
            if (c.action() == 0 && c.nbt() == null) {
                TreeMap<Long, byte[]> tm = byPos.get(new BlockPos(c.x(), c.y(), c.z()).asLong());
                if (tm != null) {
                    Map.Entry<Long, byte[]> e = tm.floorEntry(c.time() + 2000L);
                    if (e != null && Math.abs(e.getKey() - c.time()) <= 5000L) {
                        result.add(new BlockChange(c.id(), c.time(), c.material(), c.action(),
                                c.x(), c.y(), c.z(), c.sourceType(), c.extraData(), e.getValue()));
                        continue;
                    }
                }
            }
            result.add(c);
        }
        return result;
    }

    /** @param reverse см. {@link #queryBlocks(Connection, RollbackFilter, boolean)}. */
    public static List<ItemChange> queryItems(Connection conn, RollbackFilter f, boolean reverse) throws SQLException {
        // Фильтр действий: контейнеры — единая категория; если она не проходит, предметы не трогаем.
        if (!ActionFilters.allows(f, ActionFilters.CONTAINER)) return new ArrayList<>();
        // У containers нет source_type — фильтр по источнику к предметам не применяем.
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.time, m.name, c.action, c.x, c.y, c.z, c.data, c.amount
                FROM containers c
                INNER JOIN levels l ON c.level = l.id
                LEFT JOIN materials m ON c.type = m.id
                WHERE l.name = ? AND c.time BETWEEN ? AND ?
                  AND c.x BETWEEN ? AND ? AND c.y BETWEEN ? AND ? AND c.z BETWEEN ? AND ?
                  AND COALESCE(c.rolled_back, 0) = ?
                """);
        appendPlayerFilters(sql, f, "c.user");
        appendMaterialFilters(sql, f);
        sql.append(reverse ? " ORDER BY c.time DESC" : " ORDER BY c.time ASC");

        List<ItemChange> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindCommon(ps, f, reverse ? 0 : 1);
            i = bindPlayerParams(ps, f, i);
            i = bindMaterials(ps, f, i);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ItemChange(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7),
                            rs.getBytes(8), rs.getInt(9)));
                }
            }
        }
        return out;
    }

    // --- построение фильтров ---

    private static void appendFilters(StringBuilder sql, RollbackFilter f, String alias) {
        appendPlayerFilters(sql, f, alias + ".user");
        if (f.sourceType != null) sql.append(" AND ").append(alias).append(".source_type = ?");
        appendMaterialFilters(sql, f);
    }

    private static void appendPlayerFilters(StringBuilder sql, RollbackFilter f, String col) {
        if (!f.playerNames.isEmpty())
            sql.append(" AND ").append(col).append(" IN (SELECT id FROM users WHERE name IN (")
               .append(placeholders(f.playerNames.size())).append("))");
        if (!f.excludePlayerNames.isEmpty())
            sql.append(" AND ").append(col).append(" NOT IN (SELECT id FROM users WHERE name IN (")
               .append(placeholders(f.excludePlayerNames.size())).append("))");
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static void appendMaterialFilters(StringBuilder sql, RollbackFilter f) {
        sql.append(MaterialMatcher.whereFragment("m.name", f.includeMaterials, f.excludeMaterials));
    }

    private static int bindAll(PreparedStatement ps, RollbackFilter f, int rolledBack) throws SQLException {
        int i = bindCommon(ps, f, rolledBack);
        i = bindPlayerParams(ps, f, i);
        if (f.sourceType != null) ps.setString(i++, f.sourceType);
        return bindMaterials(ps, f, i);
    }

    private static int bindPlayerParams(PreparedStatement ps, RollbackFilter f, int i) throws SQLException {
        for (String name : f.playerNames) ps.setString(i++, name);
        for (String name : f.excludePlayerNames) ps.setString(i++, name);
        return i;
    }

    private static int bindMaterials(PreparedStatement ps, RollbackFilter f, int i) throws SQLException {
        return MaterialMatcher.bind(ps, MaterialMatcher.params(f.includeMaterials, f.excludeMaterials), i);
    }

    private static int bindCommon(PreparedStatement ps, RollbackFilter f, int rolledBack) throws SQLException {
        ps.setString(1, f.levelName);
        ps.setLong(2, f.timeFrom);
        ps.setLong(3, f.timeTo);
        ps.setInt(4, f.minX); ps.setInt(5, f.maxX);
        ps.setInt(6, f.minY); ps.setInt(7, f.maxY);
        ps.setInt(8, f.minZ); ps.setInt(9, f.maxZ);
        ps.setInt(10, rolledBack);
        return 11;
    }

    /** Список int'ов через запятую для безопасной подстановки кодов действий в {@code IN (...)}. */
    private static String intList(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < values.size(); k++) {
            if (k > 0) sb.append(',');
            sb.append(values.get(k).intValue());
        }
        return sb.toString();
    }

    // --- семантика отката ------------------------------------------------------

    /**
     * Какая запись определяет ИТОГОВОЕ состояние каждой позиции после полного отката.
     * <p>
     * Откат возвращает территорию к снимку на момент {@code timeFrom}: он применяет обратные
     * операции от новых записей к старым, поэтому последней применяется САМАЯ СТАРАЯ запись —
     * она и задаёт результат. Промежуточные состояния значения не имеют.
     * <p>
     * Превью раньше брало самую НОВУЮ запись на позицию и показывало состояние, которого после
     * отката не будет: например блок, поставленный в середине окна, хотя на начало окна там
     * было пусто.
     *
     * @param newestFirst записи в порядке выборки — от новых к старым
     */
    public static Map<BlockPos, BlockChange> finalChangePerPosition(List<BlockChange> newestFirst) {
        Map<BlockPos, BlockChange> out = new LinkedHashMap<>();
        for (BlockChange ch : newestFirst) {
            // Кладём без проверки: каждая следующая запись старее, последняя запись победит.
            out.put(new BlockPos(ch.x(), ch.y(), ch.z()), ch);
        }
        return out;
    }

    // --- пометка rolled_back (для зачёркивания в lookup, как в CoreProtect) ---

    /**
     * Пометить строки по их id. Раньше пометка шла «по фильтру» — то есть накрывала и те строки,
     * для которых восстановление на самом деле НЕ удалось (контейнер исчез, блок не резолвится).
     * Такие строки считались откатанными и выпадали из повторной попытки. Теперь помечаем ровно то,
     * что реально применилось.
     *
     * @param table только "blocks" или "containers" (внутренний литерал, не пользовательский ввод).
     * @param value 1 = откатано, 0 = restore вернул.
     */
    public static int markRolledBack(Connection conn, String table, List<Long> ids, int value) throws SQLException {
        if (ids.isEmpty()) return 0;
        final int CHUNK = 500;   // держим IN (...) в разумных пределах для обоих драйверов
        int total = 0;
        for (int off = 0; off < ids.size(); off += CHUNK) {
            List<Long> part = ids.subList(off, Math.min(off + CHUNK, ids.size()));
            String sql = "UPDATE " + table + " SET rolled_back = ? WHERE id IN ("
                    + placeholders(part.size()) + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, value);
                int i = 2;
                for (Long id : part) ps.setLong(i++, id);
                total += ps.executeUpdate();
            }
        }
        return total;
    }
}
