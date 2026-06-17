package com.gle.rollback;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
            long time, String material, int action,
            int x, int y, int z,
            @Nullable String sourceType, @Nullable String extraData, byte @Nullable [] nbt
    ) {}

    /** Изменение содержимого контейнера для реверса. */
    public record ItemChange(
            long time, String material, int action,
            int x, int y, int z,
            byte @Nullable [] data, int amount
    ) {}

    public static List<BlockChange> queryBlocks(Connection conn, RollbackFilter f) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT b.time, m.name, b.action, b.x, b.y, b.z, b.source_type, b.extra_data, b.block_nbt
                FROM blocks b
                INNER JOIN levels l ON b.level = l.id
                LEFT JOIN materials m ON b.type = m.id
                WHERE l.name = ? AND b.time BETWEEN ? AND ?
                  AND b.x BETWEEN ? AND ? AND b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ?
                  AND b.action IN (0, 1)
                """);
        appendFilters(sql, f, "b");
        sql.append(" ORDER BY b.time DESC");

        List<BlockChange> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindAll(ps, f);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BlockChange(
                            rs.getLong(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getString(7), rs.getString(8), rs.getBytes(9)));
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
                        result.add(new BlockChange(c.time(), c.material(), c.action(),
                                c.x(), c.y(), c.z(), c.sourceType(), c.extraData(), e.getValue()));
                        continue;
                    }
                }
            }
            result.add(c);
        }
        return result;
    }

    public static List<ItemChange> queryItems(Connection conn, RollbackFilter f) throws SQLException {
        // У containers нет source_type — фильтр по источнику к предметам не применяем.
        StringBuilder sql = new StringBuilder("""
                SELECT c.time, m.name, c.action, c.x, c.y, c.z, c.data, c.amount
                FROM containers c
                INNER JOIN levels l ON c.level = l.id
                LEFT JOIN materials m ON c.type = m.id
                WHERE l.name = ? AND c.time BETWEEN ? AND ?
                  AND c.x BETWEEN ? AND ? AND c.y BETWEEN ? AND ? AND c.z BETWEEN ? AND ?
                """);
        boolean byPlayer = f.playerName != null;
        if (byPlayer) sql.append(" AND c.user = (SELECT id FROM users WHERE name = ?)");
        appendMaterialFilters(sql, f);
        sql.append(" ORDER BY c.time DESC");

        List<ItemChange> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindCommon(ps, f);
            if (byPlayer) ps.setString(i++, f.playerName);
            i = bindMaterials(ps, f, i);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ItemChange(
                            rs.getLong(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getBytes(7), rs.getInt(8)));
                }
            }
        }
        return out;
    }

    // --- построение фильтров ---

    private static void appendFilters(StringBuilder sql, RollbackFilter f, String alias) {
        if (f.playerName != null) sql.append(" AND ").append(alias).append(".user = (SELECT id FROM users WHERE name = ?)");
        if (f.sourceType != null) sql.append(" AND ").append(alias).append(".source_type = ?");
        appendMaterialFilters(sql, f);
    }

    private static void appendMaterialFilters(StringBuilder sql, RollbackFilter f) {
        if (!f.includeMaterials.isEmpty()) {
            sql.append(" AND m.name IN (").append(placeholders(f.includeMaterials.size())).append(")");
        }
        if (!f.excludeMaterials.isEmpty()) {
            sql.append(" AND (m.name IS NULL OR m.name NOT IN (").append(placeholders(f.excludeMaterials.size())).append("))");
        }
    }

    private static int bindAll(PreparedStatement ps, RollbackFilter f) throws SQLException {
        int i = bindCommon(ps, f);
        if (f.playerName != null) ps.setString(i++, f.playerName);
        if (f.sourceType != null) ps.setString(i++, f.sourceType);
        return bindMaterials(ps, f, i);
    }

    private static int bindMaterials(PreparedStatement ps, RollbackFilter f, int i) throws SQLException {
        for (String m : f.includeMaterials) ps.setString(i++, m);
        for (String m : f.excludeMaterials) ps.setString(i++, m);
        return i;
    }

    private static int bindCommon(PreparedStatement ps, RollbackFilter f) throws SQLException {
        ps.setString(1, f.levelName);
        ps.setLong(2, f.timeFrom);
        ps.setLong(3, f.timeTo);
        ps.setInt(4, f.minX); ps.setInt(5, f.maxX);
        ps.setInt(6, f.minY); ps.setInt(7, f.maxY);
        ps.setInt(8, f.minZ); ps.setInt(9, f.maxZ);
        return 10;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    // --- пометка rolled_back (для зачёркивания в lookup, как в CoreProtect) ---

    /** Пометить откатанные строки blocks. value: 1 = откатано, 0 = restore вернул. */
    public static int markBlocksRolledBack(Connection conn, RollbackFilter f, int value) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE blocks SET rolled_back = ? "
                + "WHERE level = (SELECT id FROM levels WHERE name = ?) AND time BETWEEN ? AND ? "
                + "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND action IN (0,1)");
        if (f.playerName != null) sql.append(" AND user = (SELECT id FROM users WHERE name = ?)");
        if (f.sourceType != null) sql.append(" AND source_type = ?");
        appendMaterialSubqueries(sql, f);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindUpdate(ps, f, value);
            if (f.playerName != null) ps.setString(i++, f.playerName);
            if (f.sourceType != null) ps.setString(i++, f.sourceType);
            bindMaterials(ps, f, i);
            return ps.executeUpdate();
        }
    }

    /** Пометить откатанные строки containers. */
    public static int markContainersRolledBack(Connection conn, RollbackFilter f, int value) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE containers SET rolled_back = ? "
                + "WHERE level = (SELECT id FROM levels WHERE name = ?) AND time BETWEEN ? AND ? "
                + "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
        if (f.playerName != null) sql.append(" AND user = (SELECT id FROM users WHERE name = ?)");
        appendMaterialSubqueries(sql, f);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = bindUpdate(ps, f, value);
            if (f.playerName != null) ps.setString(i++, f.playerName);
            bindMaterials(ps, f, i);
            return ps.executeUpdate();
        }
    }

    private static void appendMaterialSubqueries(StringBuilder sql, RollbackFilter f) {
        if (!f.includeMaterials.isEmpty())
            sql.append(" AND type IN (SELECT id FROM materials WHERE name IN (").append(placeholders(f.includeMaterials.size())).append("))");
        if (!f.excludeMaterials.isEmpty())
            sql.append(" AND type NOT IN (SELECT id FROM materials WHERE name IN (").append(placeholders(f.excludeMaterials.size())).append("))");
    }

    private static int bindUpdate(PreparedStatement ps, RollbackFilter f, int value) throws SQLException {
        ps.setInt(1, value);
        ps.setString(2, f.levelName);
        ps.setLong(3, f.timeFrom);
        ps.setLong(4, f.timeTo);
        ps.setInt(5, f.minX); ps.setInt(6, f.maxX);
        ps.setInt(7, f.minY); ps.setInt(8, f.maxY);
        ps.setInt(9, f.minZ); ps.setInt(10, f.maxZ);
        return 11;
    }
}
