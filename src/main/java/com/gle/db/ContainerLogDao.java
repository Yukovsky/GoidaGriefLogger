package com.gle.db;

import com.gle.core.db.IdCache;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.Types;

/**
 * Запись перемещений предметов в таблицы {@code containers}/{@code items} GriefLogger.
 * Формат строк — как у GL; id справочников ({@code materials}/{@code levels}/{@code users})
 * берутся из {@link IdCache} (Фаза 2): вставка в справочник — только при первом появлении,
 * горячая вставка кладёт готовые int-id напрямую.
 */
public final class ContainerLogDao {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/ContainerLogDao");

    private final GLDatabase db;
    private final WriteQueue queue;
    private final IdCache ids;

    public ContainerLogDao(GLDatabase db, WriteQueue queue, IdCache ids) {
        this.db = db;
        this.queue = queue;
        this.ids = ids;
    }

    /** Снимок одной записи контейнера. */
    public record ContainerEntry(
            long time,
            String userUuid,
            String levelName,
            int x, int y, int z,
            String material,        // нормализованное имя (без minecraft:)
            byte @Nullable [] data, // компоненты предмета (формат GL)
            int amount,
            int action              // ItemAction: REMOVE_ITEM=0 / ADD_ITEM=1 / ...
    ) {}

    /** Запись в таблицу {@code containers} (взаимодействия с контейнерами). */
    public void insert(ContainerEntry e) {
        insertInto("containers", e);
    }

    /**
     * Запись в таблицу {@code items} GriefLogger (выбросы/подбор/крафт/съедание — действия с
     * предметами «в руках/на земле», привязанные к позиции игрока, а не к контейнеру).
     */
    public void insertItem(ContainerEntry e) {
        insertInto("items", e);
    }

    private void insertInto(String table, ContainerEntry e) {
        final String insSql = "INSERT INTO " + table + "(time, user, level, x, y, z, type, data, amount, action) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        queue.submit(conn -> {
            Integer userId = ids.userId(conn, e.userUuid());
            if (userId == null) {
                LOGGER.debug("{}: пропуск — нет пользователя uuid={}", table, e.userUuid());
                return;
            }
            int levelId = ids.levelId(conn, e.levelName());
            int materialId = ids.materialId(conn, e.material());
            try (PreparedStatement c = conn.prepareStatement(insSql)) {
                c.setLong(1, e.time());
                c.setInt(2, userId);
                c.setInt(3, levelId);
                c.setInt(4, e.x());
                c.setInt(5, e.y());
                c.setInt(6, e.z());
                c.setInt(7, materialId);
                if (e.data() != null) c.setBytes(8, e.data());
                else c.setNull(8, Types.BLOB);
                c.setInt(9, e.amount());
                c.setInt(10, e.action());
                c.executeUpdate();
            }
        });
    }
}
