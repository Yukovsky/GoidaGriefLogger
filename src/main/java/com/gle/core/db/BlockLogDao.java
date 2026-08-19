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
    private final NbtStore nbt;

    public BlockLogDao(GLDatabase db, WriteQueue queue, IdCache ids, NbtStore nbt) {
        this.db = db;
        this.queue = queue;
        this.ids = ids;
        this.nbt = nbt;
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
        // Обычный INSERT: у blocks нет UNIQUE, дедупликации IGNORE не давал — только глушил
        // ошибки (в MySQL INSERT IGNORE понижает до warning в т.ч. truncation и NOT NULL).
        final String blockSql = "INSERT INTO blocks("
                + "time, user, level, x, y, z, type, action, "
                + "source_type, source_player_uuid, extra_data, block_nbt, nbt_truncated) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        queue.submit((conn, sink) -> {
            Integer userId = ids.userIdOrCreate(conn, e.userUuid());
            if (userId == null) {
                // blocks.user NOT NULL: пользователя ещё нет — пропускаем (так же поступал
                // подзапрос GriefLogger, дававший NULL и роняя вставку по NOT NULL).
                LOGGER.debug("blocks: пропуск — нет пользователя uuid={}", e.userUuid());
                return;
            }
            int levelId = ids.levelId(conn, e.levelName());
            int materialId = ids.materialId(conn, e.material());

            PreparedStatement b = sink.statement(blockSql);
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
            b.addBatch();
        });
    }

    /**
     * Запись убийства сущности игроком в таблицу {@code blocks} (action KILL=3).
     * <p>
     * Повторяет приём GriefLogger: имя сущности живёт в справочнике {@code entities}, и для kill-строк
     * колонка {@code blocks.type} ссылается на {@code entities.id} (а не на {@code materials.id}).
     * Инспектор различает это по {@code action == KILL_ENTITY}.
     */
    /**
     * @param entityNbt очищенный снимок сущности или {@code null}, если она ничем не отличается
     *                  от обычной особи своего типа. Снимок кладётся в дедуплицирующее хранилище,
     *                  строка ссылается на него по id — одинаковые снимки не размножаются.
     */
    public void insertEntityKill(long time, String userUuid, String levelName,
                                 int x, int y, int z, String entityName, byte @Nullable [] entityNbt) {
        final String blockSql = "INSERT INTO blocks(time, user, level, x, y, z, type, action, nbt_id) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";

        queue.submit((conn, sink) -> {
            Integer userId = ids.userIdOrCreate(conn, userUuid);
            if (userId == null) {
                LOGGER.debug("blocks(kill): пропуск — нет пользователя uuid={}", userUuid);
                return;
            }
            int levelId = ids.levelId(conn, levelName);
            int entityId = ids.entityId(conn, entityName); // type → entities.id для kill-строк
            PreparedStatement b = sink.statement(blockSql);
            b.setLong(1, time);
            b.setInt(2, userId);
            b.setInt(3, levelId);
            b.setInt(4, x);
            b.setInt(5, y);
            b.setInt(6, z);
            b.setInt(7, entityId);
            b.setInt(8, GLActions.KILL_ENTITY);
            // Снимок кладётся в дедуплицирующее хранилище; null означает «обычная особь».
            Integer nbtId = nbt.idFor(conn, entityNbt);
            if (nbtId == null) b.setNull(9, Types.INTEGER); else b.setInt(9, nbtId);
            b.addBatch();
        });
    }

    private static void setNullableString(PreparedStatement ps, int idx, @Nullable String v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }
}
