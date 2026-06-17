package com.gle.db;

import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Запись перемещений предметов в таблицу {@code containers} GriefLogger.
 * Повторяет формат GL: {@code INSERT OR IGNORE INTO materials}, затем
 * {@code INSERT INTO containers(time, user, level, x, y, z, type, data, amount, action)}.
 */
public final class ContainerLogDao {

    private final GLDatabase db;
    private final WriteQueue queue;

    public ContainerLogDao(GLDatabase db, WriteQueue queue) {
        this.db = db;
        this.queue = queue;
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
        final boolean mysql = db.isMysql();
        final String matSql = (mysql ? "INSERT IGNORE" : "INSERT OR IGNORE") + " INTO materials(name) VALUES(?)";
        final String lvlSql = (mysql ? "INSERT IGNORE" : "INSERT OR IGNORE") + " INTO levels(name) VALUES(?)";
        final String insSql = "INSERT INTO " + table + "(time, user, level, x, y, z, type, data, amount, action) "
                + "VALUES(?, (SELECT id FROM users WHERE uuid = ?), (SELECT id FROM levels WHERE name = ?), "
                + "?, ?, ?, (SELECT id FROM materials WHERE name = ?), ?, ?, ?)";

        queue.submit(conn -> {
            try (PreparedStatement mat = conn.prepareStatement(matSql)) {
                mat.setString(1, e.material());
                mat.executeUpdate();
            }
            try (PreparedStatement lvl = conn.prepareStatement(lvlSql)) {
                lvl.setString(1, e.levelName());
                lvl.executeUpdate();
            }
            try (PreparedStatement c = conn.prepareStatement(insSql)) {
                c.setLong(1, e.time());
                c.setString(2, e.userUuid());
                c.setString(3, e.levelName());
                c.setInt(4, e.x());
                c.setInt(5, e.y());
                c.setInt(6, e.z());
                c.setString(7, e.material());
                if (e.data() != null) c.setBytes(8, e.data());
                else c.setNull(8, Types.BLOB);
                c.setInt(9, e.amount());
                c.setInt(10, e.action());
                c.executeUpdate();
            }
        });
    }
}
