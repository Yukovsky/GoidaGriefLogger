package com.gle.db;

/**
 * Запись игровых сессий (вход/выход игрока) в таблицу {@code sessions}.
 * <p>
 * Часть поглощения GriefLogger (Путь E): раньше сессии писал сам GL своим хрупким единым
 * соединением; теперь это делает ЕДИНЫЙ писатель {@link WriteQueue} — тот же асинхронный путь,
 * что и для всех остальных событий. На вход также обеспечиваем существование пользователя
 * (справочник {@code users}) и истории имён ({@code usernames}), как делал GL.
 */
public final class SessionDao {

    private final GLDatabase db;
    private final WriteQueue queue;

    public SessionDao(GLDatabase db, WriteQueue queue) {
        this.db = db;
        this.queue = queue;
    }

    /** Снимок сессии, снятый на игровом потоке. */
    public record SessionEntry(
            long time,
            String playerName,
            String playerUuid,
            String levelName,
            int x, int y, int z,
            int action            // GLActions.SESSION_JOIN / SESSION_QUIT
    ) {}

    public void insert(SessionEntry e) {
        final boolean mysql = db.isMysql();
        final String ignore = mysql ? "INSERT IGNORE" : "INSERT OR IGNORE";

        final String userSql = ignore + " INTO users(name, uuid) VALUES(?, ?)";
        final String nameSql = ignore + " INTO usernames(time, uuid, name) VALUES(?, ?, ?)";
        final String lvlSql  = ignore + " INTO levels(name) VALUES(?)";
        final String sesSql  = ignore + " INTO sessions(time, user, level, x, y, z, action) "
                + "VALUES(?, (SELECT id FROM users WHERE uuid = ?), (SELECT id FROM levels WHERE name = ?), ?, ?, ?, ?)";

        queue.submit(conn -> {
            try (var ps = conn.prepareStatement(userSql)) {
                ps.setString(1, e.playerName());
                ps.setString(2, e.playerUuid());
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(nameSql)) {
                ps.setLong(1, e.time());
                ps.setString(2, e.playerUuid());
                ps.setString(3, e.playerName());
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(lvlSql)) {
                ps.setString(1, e.levelName());
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(sesSql)) {
                ps.setLong(1, e.time());
                ps.setString(2, e.playerUuid());
                ps.setString(3, e.levelName());
                ps.setInt(4, e.x());
                ps.setInt(5, e.y());
                ps.setInt(6, e.z());
                ps.setInt(7, e.action());
                ps.executeUpdate();
            }
        });
    }
}
