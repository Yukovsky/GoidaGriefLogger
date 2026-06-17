package com.gle.db;

import com.gle.core.GLActions;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Запись изменений блоков в таблицу {@code blocks} GriefLogger с расширениями GLE.
 * <p>
 * Повторяет приём GriefLogger: {@code INSERT OR IGNORE INTO materials/levels} для гарантии
 * существования внешних ключей, затем вставка строки {@code blocks} с подзапросами по id.
 * Дополнительно заполняет колонки GLE: {@code source_type}, {@code source_player_uuid},
 * {@code extra_data}, {@code block_nbt}, {@code nbt_truncated}.
 * <p>
 * Все данные — иммутабельный снимок, снятый на игровом потоке; сама вставка выполняется
 * на потоке {@link WriteQueue}.
 */
public final class BlockLogDao {

    private final GLDatabase db;
    private final WriteQueue queue;

    public BlockLogDao(GLDatabase db, WriteQueue queue) {
        this.db = db;
        this.queue = queue;
    }

    /** Снимок одной записи блока. */
    public record BlockEntry(
            long time,
            String userUuid,
            String levelName,
            int x, int y, int z,
            String material,        // нормализованное имя (без minecraft:)
            int action,
            @Nullable String sourceType,
            @Nullable String sourcePlayerUuid,
            @Nullable String extraData,
            byte @Nullable [] blockNbt,
            boolean nbtTruncated
    ) {}

    public void insert(BlockEntry e) {
        boolean mysql = db.isMysql();
        final String ignore = mysql ? "INSERT IGNORE" : "INSERT OR IGNORE";

        final String matSql   = ignore + " INTO materials(name) VALUES(?)";
        final String lvlSql   = ignore + " INTO levels(name) VALUES(?)";
        final String blockSql = ignore + " INTO blocks("
                + "time, user, level, x, y, z, type, action, "
                + "source_type, source_player_uuid, extra_data, block_nbt, nbt_truncated) "
                + "VALUES(?, (SELECT id FROM users WHERE uuid = ?), (SELECT id FROM levels WHERE name = ?), "
                + "?, ?, ?, (SELECT id FROM materials WHERE name = ?), ?, ?, ?, ?, ?, ?)";

        queue.submit(conn -> {
            try (PreparedStatement mat = conn.prepareStatement(matSql)) {
                mat.setString(1, e.material());
                mat.executeUpdate();
            }
            try (PreparedStatement lvl = conn.prepareStatement(lvlSql)) {
                lvl.setString(1, e.levelName());
                lvl.executeUpdate();
            }
            try (PreparedStatement b = conn.prepareStatement(blockSql)) {
                b.setLong(1, e.time());
                b.setString(2, e.userUuid());
                b.setString(3, e.levelName());
                b.setInt(4, e.x());
                b.setInt(5, e.y());
                b.setInt(6, e.z());
                b.setString(7, e.material());
                b.setInt(8, e.action());
                setNullableString(b, 9, e.sourceType());
                setNullableString(b, 10, e.sourcePlayerUuid());
                setNullableString(b, 11, e.extraData());
                if (e.blockNbt() != null) {
                    b.setBytes(12, e.blockNbt());
                } else {
                    b.setNull(12, Types.BLOB);
                }
                b.setInt(13, e.nbtTruncated() ? 1 : 0);
                b.executeUpdate();
            }
        });
    }

    /**
     * Запись убийства сущности игроком в таблицу {@code blocks} (action KILL=3).
     * <p>
     * Повторяет приём GriefLogger: имя сущности живёт в справочнике {@code entities}, и для kill-строк
     * колонка {@code blocks.type} ссылается на {@code entities.id} (а не на {@code materials.id}).
     * Инспектор различает это по {@code action == KILL_ENTITY}.
     */
    public void insertEntityKill(long time, String userUuid, String levelName,
                                 int x, int y, int z, String entityName) {
        final boolean mysql = db.isMysql();
        final String ignore = mysql ? "INSERT IGNORE" : "INSERT OR IGNORE";
        final String entSql = ignore + " INTO entities(name) VALUES(?)";
        final String lvlSql = ignore + " INTO levels(name) VALUES(?)";
        final String blockSql = ignore + " INTO blocks(time, user, level, x, y, z, type, action) "
                + "VALUES(?, (SELECT id FROM users WHERE uuid = ?), (SELECT id FROM levels WHERE name = ?), "
                + "?, ?, ?, (SELECT id FROM entities WHERE name = ?), ?)";

        queue.submit(conn -> {
            try (PreparedStatement ent = conn.prepareStatement(entSql)) {
                ent.setString(1, entityName);
                ent.executeUpdate();
            }
            try (PreparedStatement lvl = conn.prepareStatement(lvlSql)) {
                lvl.setString(1, levelName);
                lvl.executeUpdate();
            }
            try (PreparedStatement b = conn.prepareStatement(blockSql)) {
                b.setLong(1, time);
                b.setString(2, userUuid);
                b.setString(3, levelName);
                b.setInt(4, x);
                b.setInt(5, y);
                b.setInt(6, z);
                b.setString(7, entityName);
                b.setInt(8, GLActions.KILL_ENTITY);
                b.executeUpdate();
            }
        });
    }

    private static void setNullableString(PreparedStatement ps, int idx, @Nullable String v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }
}
