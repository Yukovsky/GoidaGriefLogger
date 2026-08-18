package com.gle.core.db;

import com.gle.core.db.IdCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Запись игровых сессий (вход/выход игрока) в таблицу {@code sessions}.
 * <p>
 * Часть поглощения GriefLogger (Путь E): раньше сессии писал сам GL своим хрупким единым
 * соединением; теперь это делает ЕДИНЫЙ писатель {@link WriteQueue} — тот же асинхронный путь,
 * что и для всех остальных событий. На вход также обеспечиваем существование пользователя
 * (справочник {@code users}) и истории имён ({@code usernames}), как делал GL.
 * <p>
 * Именно здесь пользователь впервые попадает в {@link IdCache} ({@code upsertUser}) — после этого
 * все остальные события игрока резолвят его id из кэша без обращения к БД.
 */
public final class SessionDao {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/SessionDao");

    private final GLDatabase db;
    private final WriteQueue queue;
    private final IdCache ids;

    public SessionDao(GLDatabase db, WriteQueue queue, IdCache ids) {
        this.db = db;
        this.queue = queue;
        this.ids = ids;
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
        final String ignore = db.isMysql() ? "INSERT IGNORE" : "INSERT OR IGNORE";
        final String nameSql = ignore + " INTO usernames(time, uuid, name) VALUES(?, ?, ?)";
        final String sesSql  = ignore + " INTO sessions(time, user, level, x, y, z, action) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?)";

        queue.submit((conn, sink) -> {
            Integer userId = ids.upsertUser(conn, e.playerUuid(), e.playerName());
            if (userId == null) {
                LOGGER.warn("sessions: не удалось обеспечить пользователя uuid={} — сессия не записана",
                        e.playerUuid());
                return;
            }
            // usernames — таблица истории имён (time-series), кэшем не покрывается; пишем как GL.
            var names = sink.statement(nameSql);
            names.setLong(1, e.time());
            names.setString(2, e.playerUuid());
            names.setString(3, e.playerName());
            names.addBatch();
            int levelId = ids.levelId(conn, e.levelName());
            var ps = sink.statement(sesSql);
            ps.setLong(1, e.time());
            ps.setInt(2, userId);
            ps.setInt(3, levelId);
            ps.setInt(4, e.x());
            ps.setInt(5, e.y());
            ps.setInt(6, e.z());
            ps.setInt(7, e.action());
            ps.addBatch();
        });
    }
}
