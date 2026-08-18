package com.gle.core.db;

import com.gle.core.db.IdCache;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Запись сложных событий GLE в собственные таблицы ({@code gle_signs}, {@code gle_world_entities},
 * {@code gle_player_deaths}). Эти события не вписываются в int-действия GriefLogger и хранятся
 * со своим {@code id} PK для удобного роллбека.
 * <p>
 * id справочников ({@code levels}/{@code users}) — из {@link IdCache} (Фаза 2). Колонка {@code user}
 * в этих таблицах НЕ {@code NOT NULL}, поэтому отсутствие пользователя пишется как {@code NULL}
 * (точно как делал подзапрос GriefLogger).
 */
public final class GleEventsDao {

    private final GLDatabase db;
    private final WriteQueue queue;
    private final IdCache ids;

    public GleEventsDao(GLDatabase db, WriteQueue queue, IdCache ids) {
        this.db = db;
        this.queue = queue;
        this.ids = ids;
    }

    private static void setNullableInt(PreparedStatement ps, int idx, @Nullable Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, v);
    }

    // --- Таблички ---
    public record SignEntry(long time, String userUuid, String levelName, int x, int y, int z,
                            String frontBefore, String backBefore, String frontAfter, String backAfter,
                            String flags) {}

    public void insertSign(SignEntry e) {
        final String sql = "INSERT INTO gle_signs(time, user, level, x, y, z, " +
                "front_before, back_before, front_after, back_after, flags) " +
                "VALUES(?, ?, ?, ?,?,?, ?,?,?,?, ?)";
        queue.submit((conn, sink) -> {
            Integer userId = ids.userIdOrCreate(conn, e.userUuid());
            int levelId = ids.levelId(conn, e.levelName());
            PreparedStatement p = sink.statement(sql);
            p.setLong(1, e.time());
            setNullableInt(p, 2, userId);
            p.setInt(3, levelId);
            p.setInt(4, e.x()); p.setInt(5, e.y()); p.setInt(6, e.z());
            p.setString(7, e.frontBefore()); p.setString(8, e.backBefore());
            p.setString(9, e.frontAfter()); p.setString(10, e.backAfter());
            p.setString(11, e.flags());
            p.addBatch();
        });
    }

    // --- Рамки/картины ---
    public record WorldEntityEntry(long time, String userUuid, String levelName, int x, int y, int z,
                                   String entityType, String entityUuid, String action,
                                   @Nullable String item, byte @Nullable [] itemNbt,
                                   String sourceType, @Nullable String extraData) {}

    public void insertWorldEntity(WorldEntityEntry e) {
        final String sql = "INSERT INTO gle_world_entities(time, user, level, x, y, z, " +
                "entity_type, entity_uuid, action, item, item_nbt, source_type, extra_data) " +
                "VALUES(?, ?, ?, ?,?,?, ?,?,?,?,?,?,?)";
        queue.submit((conn, sink) -> {
            Integer userId = ids.userIdOrCreate(conn, e.userUuid());
            int levelId = ids.levelId(conn, e.levelName());
            PreparedStatement p = sink.statement(sql);
            p.setLong(1, e.time());
            setNullableInt(p, 2, userId);
            p.setInt(3, levelId);
            p.setInt(4, e.x()); p.setInt(5, e.y()); p.setInt(6, e.z());
            p.setString(7, e.entityType()); p.setString(8, e.entityUuid()); p.setString(9, e.action());
            if (e.item() == null) p.setNull(10, Types.VARCHAR); else p.setString(10, e.item());
            if (e.itemNbt() == null) p.setNull(11, Types.BLOB); else p.setBytes(11, e.itemNbt());
            p.setString(12, e.sourceType());
            if (e.extraData() == null) p.setNull(13, Types.VARCHAR); else p.setString(13, e.extraData());
            p.addBatch();
        });
    }

    // --- NBT-снимок контейнера на момент слома (для отката сломов игроком) ---
    public void insertBlockNbt(long time, String levelName, int x, int y, int z, byte[] nbt) {
        final String sql = "INSERT INTO gle_block_nbt(time, level, x, y, z, nbt) VALUES(?, ?, ?, ?, ?, ?)";
        queue.submit((conn, sink) -> {
            PreparedStatement p = sink.statement(sql);
            p.setLong(1, time);
            p.setString(2, levelName);
            p.setInt(3, x); p.setInt(4, y); p.setInt(5, z);
            p.setBytes(6, nbt);
            p.addBatch();
        });
    }

    // --- Смерть игрока ---
    public record PlayerDeathEntry(long time, String playerUuid, String levelName, int x, int y, int z,
                                   String cause, byte @Nullable [] inventoryNbt) {}

    public void insertPlayerDeath(PlayerDeathEntry e) {
        final String sql = "INSERT INTO gle_player_deaths(time, player_uuid, level, x, y, z, cause, inventory_nbt) " +
                "VALUES(?, ?, ?, ?,?,?, ?, ?)";
        queue.submit((conn, sink) -> {
            int levelId = ids.levelId(conn, e.levelName());
            PreparedStatement p = sink.statement(sql);
            p.setLong(1, e.time());
            p.setString(2, e.playerUuid());
            p.setInt(3, levelId);
            p.setInt(4, e.x()); p.setInt(5, e.y()); p.setInt(6, e.z());
            p.setString(7, e.cause());
            if (e.inventoryNbt() == null) p.setNull(8, Types.BLOB); else p.setBytes(8, e.inventoryNbt());
            p.addBatch();
        });
    }
}
