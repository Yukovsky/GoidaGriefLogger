package com.gle.db;

import java.sql.PreparedStatement;

/**
 * Запись текстовых логов игрока — чат ({@code chats}) и команды ({@code commands}) — на единый writer.
 * Раньше их писал GriefLogger. Обе таблицы одинаковой формы: time, user, level, x, y, z, message.
 * Сообщение усекается до 256 символов (ширина {@code varchar(256)} в MySQL у GL).
 */
public final class TextLogDao {

    private static final int MAX_MESSAGE = 256;

    private final GLDatabase db;
    private final WriteQueue queue;

    public TextLogDao(GLDatabase db, WriteQueue queue) {
        this.db = db;
        this.queue = queue;
    }

    public record TextEntry(
            long time,
            String userUuid,
            String levelName,
            int x, int y, int z,
            String message
    ) {}

    public void insertChat(TextEntry e) {
        insertInto("chats", e);
    }

    public void insertCommand(TextEntry e) {
        insertInto("commands", e);
    }

    private void insertInto(String table, TextEntry e) {
        final boolean mysql = db.isMysql();
        final String lvlSql = (mysql ? "INSERT IGNORE" : "INSERT OR IGNORE") + " INTO levels(name) VALUES(?)";
        final String insSql = "INSERT INTO " + table + "(time, user, level, x, y, z, message) "
                + "VALUES(?, (SELECT id FROM users WHERE uuid = ?), (SELECT id FROM levels WHERE name = ?), ?, ?, ?, ?)";
        final String message = truncate(e.message());

        queue.submit(conn -> {
            try (PreparedStatement lvl = conn.prepareStatement(lvlSql)) {
                lvl.setString(1, e.levelName());
                lvl.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                ps.setLong(1, e.time());
                ps.setString(2, e.userUuid());
                ps.setString(3, e.levelName());
                ps.setInt(4, e.x());
                ps.setInt(5, e.y());
                ps.setInt(6, e.z());
                ps.setString(7, message);
                ps.executeUpdate();
            }
        });
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE);
    }
}
