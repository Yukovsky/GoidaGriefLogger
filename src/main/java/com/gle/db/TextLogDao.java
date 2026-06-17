package com.gle.db;

import com.gle.core.db.IdCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;

/**
 * Запись текстовых логов игрока — чат ({@code chats}) и команды ({@code commands}) — на единый writer.
 * Раньше их писал GriefLogger. Обе таблицы одинаковой формы: time, user, level, x, y, z, message.
 * Сообщение усекается до 256 символов (ширина {@code varchar(256)} в MySQL у GL).
 * id справочников ({@code levels}/{@code users}) — из {@link IdCache} (Фаза 2).
 */
public final class TextLogDao {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/TextLogDao");

    private static final int MAX_MESSAGE = 256;

    private final GLDatabase db;
    private final WriteQueue queue;
    private final IdCache ids;

    public TextLogDao(GLDatabase db, WriteQueue queue, IdCache ids) {
        this.db = db;
        this.queue = queue;
        this.ids = ids;
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
        final String insSql = "INSERT INTO " + table + "(time, user, level, x, y, z, message) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?)";
        final String message = truncate(e.message());

        queue.submit(conn -> {
            Integer userId = ids.userId(conn, e.userUuid());
            if (userId == null) {
                LOGGER.debug("{}: пропуск — нет пользователя uuid={}", table, e.userUuid());
                return;
            }
            int levelId = ids.levelId(conn, e.levelName());
            try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                ps.setLong(1, e.time());
                ps.setInt(2, userId);
                ps.setInt(3, levelId);
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
