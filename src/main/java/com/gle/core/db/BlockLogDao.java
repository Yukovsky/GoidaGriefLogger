package com.gle.core.db;

import com.gle.core.GLActions;
import com.gle.core.db.IdCache;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Запись изменений блоков в таблицу {@code blocks} GriefLogger с расширениями GLE.
 * <p>
 * Фаза 2 (docs/06 §6): id справочников ({@code materials}/{@code levels}/{@code users}) берутся
 * из {@link IdCache} — вставка в справочник идёт только при первом появлении имени, а горячая
 * вставка {@code blocks} кладёт готовые int-id напрямую (без {@code INSERT IGNORE} на каждое
 * событие и без подзапросов {@code (SELECT id ...)}).
 * Дополнительно заполняет колонки GLE: {@code source_type}, {@code source_player_uuid},
 * {@code extra_data}, {@code block_nbt}, {@code nbt_truncated}.
 * <p>
 * Все данные — иммутабельный снимок, снятый на игровом потоке; разрешение id и сама вставка
 * выполняются на потоке {@link WriteQueue}.
 */
public final class BlockLogDao {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/BlockLogDao");

    private final GLDatabase db;
    private final WriteQueue queue;
    private final IdCache ids;

    public BlockLogDao(GLDatabase db, WriteQueue queue, IdCache ids) {
        this.db = db;
        this.queue = queue;
        this.ids = ids;
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
        final String ignore = db.isMysql() ? "INSERT IGNORE" : "INSERT OR IGNORE";
        final String blockSql = ignore + " INTO blocks("
                + "time, user, level, x, y, z, type, action, "
                + "source_type, source_player_uuid, extra_data, block_nbt, nbt_truncated) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        queue.submit(conn -> {
            Integer userId = ids.userId(conn, e.userUuid());
            if (userId == null) {
                // blocks.user NOT NULL: пользователя ещё нет — пропускаем (так же поступал
                // подзапрос GriefLogger, дававший NULL и роняя вставку по NOT NULL).
                LOGGER.debug("blocks: пропуск — нет пользователя uuid={}", e.userUuid());
                return;
            }
            int levelId = ids.levelId(conn, e.levelName());
            int materialId = ids.materialId(conn, e.material());

            try (PreparedStatement b = conn.prepareStatement(blockSql)) {
                b.setLong(1, e.time());
                b.setInt(2, userId);
                b.setInt(3, levelId);
                b.setInt(4, e.x());
                b.setInt(5, e.y());
                b.setInt(6, e.z());
                b.setInt(7, materialId);
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
        final String ignore = db.isMysql() ? "INSERT IGNORE" : "INSERT OR IGNORE";
        final String blockSql = ignore + " INTO blocks(time, user, level, x, y, z, type, action) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?)";

        queue.submit(conn -> {
            Integer userId = ids.userId(conn, userUuid);
            if (userId == null) {
                LOGGER.debug("blocks(kill): пропуск — нет пользователя uuid={}", userUuid);
                return;
            }
            int levelId = ids.levelId(conn, levelName);
            int entityId = ids.entityId(conn, entityName); // type → entities.id для kill-строк
            try (PreparedStatement b = conn.prepareStatement(blockSql)) {
                b.setLong(1, time);
                b.setInt(2, userId);
                b.setInt(3, levelId);
                b.setInt(4, x);
                b.setInt(5, y);
                b.setInt(6, z);
                b.setInt(7, entityId);
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
