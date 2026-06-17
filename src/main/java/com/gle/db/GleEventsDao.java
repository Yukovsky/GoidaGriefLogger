package com.gle.db;

import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Запись сложных событий GLE в собственные таблицы ({@code gle_signs}, {@code gle_world_entities},
 * {@code gle_player_deaths}). Эти события не вписываются в int-действия GriefLogger и хранятся
 * со своим {@code id} PK для удобного роллбека.
 */
public final class GleEventsDao {

    private final GLDatabase db;
    private final WriteQueue queue;

    public GleEventsDao(GLDatabase db, WriteQueue queue) {
        this.db = db;
        this.queue = queue;
    }

    private String ignoreLevel() {
        return (db.isMysql() ? "INSERT IGNORE" : "INSERT OR IGNORE") + " INTO levels(name) VALUES(?)";
    }

    // --- Таблички ---
    public record SignEntry(long time, String userUuid, String levelName, int x, int y, int z,
                            String frontBefore, String backBefore, String frontAfter, String backAfter,
                            String flags) {}

    public void insertSign(SignEntry e) {
        final String lvl = ignoreLevel();
        final String sql = "INSERT INTO gle_signs(time, user, level, x, y, z, " +
                "front_before, back_before, front_after, back_after, flags) " +
                "VALUES(?, (SELECT id FROM users WHERE uuid=?), (SELECT id FROM levels WHERE name=?), ?,?,?, ?,?,?,?, ?)";
        queue.submit(conn -> {
            try (PreparedStatement l = conn.prepareStatement(lvl)) { l.setString(1, e.levelName()); l.executeUpdate(); }
            try (PreparedStatement p = conn.prepareStatement(sql)) {
                p.setLong(1, e.time());
                p.setString(2, e.userUuid());
                p.setString(3, e.levelName());
                p.setInt(4, e.x()); p.setInt(5, e.y()); p.setInt(6, e.z());
                p.setString(7, e.frontBefore()); p.setString(8, e.backBefore());
                p.setString(9, e.frontAfter()); p.setString(10, e.backAfter());
                p.setString(11, e.flags());
                p.executeUpdate();
            }
        });
    }

    // --- Рамки/картины ---
    public record WorldEntityEntry(long time, String userUuid, String levelName, int x, int y, int z,
                                   String entityType, String entityUuid, String action,
                                   @Nullable String item, byte @Nullable [] itemNbt,
                                   String sourceType, @Nullable String extraData) {}

    public void insertWorldEntity(WorldEntityEntry e) {
        final String lvl = ignoreLevel();
        final String sql = "INSERT INTO gle_world_entities(time, user, level, x, y, z, " +
                "entity_type, entity_uuid, action, item, item_nbt, source_type, extra_data) " +
                "VALUES(?, (SELECT id FROM users WHERE uuid=?), (SELECT id FROM levels WHERE name=?), ?,?,?, ?,?,?,?,?,?,?)";
        queue.submit(conn -> {
            try (PreparedStatement l = conn.prepareStatement(lvl)) { l.setString(1, e.levelName()); l.executeUpdate(); }
            try (PreparedStatement p = conn.prepareStatement(sql)) {
                p.setLong(1, e.time());
                p.setString(2, e.userUuid());
                p.setString(3, e.levelName());
                p.setInt(4, e.x()); p.setInt(5, e.y()); p.setInt(6, e.z());
                p.setString(7, e.entityType()); p.setString(8, e.entityUuid()); p.setString(9, e.action());
                if (e.item() == null) p.setNull(10, Types.VARCHAR); else p.setString(10, e.item());
                if (e.itemNbt() == null) p.setNull(11, Types.BLOB); else p.setBytes(11, e.itemNbt());
                p.setString(12, e.sourceType());
                if (e.extraData() == null) p.setNull(13, Types.VARCHAR); else p.setString(13, e.extraData());
                p.executeUpdate();
            }
        });
    }

    // --- NBT-снимок контейнера на момент слома (для отката сломов игроком) ---
    public void insertBlockNbt(long time, String levelName, int x, int y, int z, byte[] nbt) {
        final String sql = "INSERT INTO gle_block_nbt(time, level, x, y, z, nbt) VALUES(?, ?, ?, ?, ?, ?)";
        queue.submit(conn -> {
            try (PreparedStatement p = conn.prepareStatement(sql)) {
                p.setLong(1, time);
                p.setString(2, levelName);
                p.setInt(3, x); p.setInt(4, y); p.setInt(5, z);
                p.setBytes(6, nbt);
                p.executeUpdate();
            }
        });
    }

    // --- Смерть игрока ---
    public record PlayerDeathEntry(long time, String playerUuid, String levelName, int x, int y, int z,
                                   String cause, byte @Nullable [] inventoryNbt) {}

    public void insertPlayerDeath(PlayerDeathEntry e) {
        final String lvl = ignoreLevel();
        final String sql = "INSERT INTO gle_player_deaths(time, player_uuid, level, x, y, z, cause, inventory_nbt) " +
                "VALUES(?, ?, (SELECT id FROM levels WHERE name=?), ?,?,?, ?, ?)";
        queue.submit(conn -> {
            try (PreparedStatement l = conn.prepareStatement(lvl)) { l.setString(1, e.levelName()); l.executeUpdate(); }
            try (PreparedStatement p = conn.prepareStatement(sql)) {
                p.setLong(1, e.time());
                p.setString(2, e.playerUuid());
                p.setString(3, e.levelName());
                p.setInt(4, e.x()); p.setInt(5, e.y()); p.setInt(6, e.z());
                p.setString(7, e.cause());
                if (e.inventoryNbt() == null) p.setNull(8, Types.BLOB); else p.setBytes(8, e.inventoryNbt());
                p.executeUpdate();
            }
        });
    }
}
