package com.gle.core.db;

import com.gle.core.db.IdCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Запись текстовых логов игрока — чат ({@code chats}) и команды ({@code commands}) — на единый writer.
 * Раньше их писал GriefLogger. Таблицы формы: time, user, level, x, y, z, &lt;текстовая колонка&gt;.
 * Сообщение усекается до 256 символов (ширина {@code varchar(256)} в MySQL у GL).
 * id справочников ({@code levels}/{@code users}) — из {@link IdCache} (Фаза 2).
 * <p>
 * <b>Имя текстовой колонки не зашито.</b> Исторический GriefLogger называл её по-разному:
 * {@code chats.message}, но {@code commands.command}. Свежие БД, созданные самим GLE
 * ({@link SchemaMigrator}), используют {@code message} в обеих таблицах. Поэтому имя определяется
 * по реальной схеме БД и кэшируется (см. {@link #resolveMessageColumn}) — иначе вставка в
 * {@code commands} существующей БД GL падает с «table commands has no column named message».
 */
public final class TextLogDao {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/TextLogDao");

    private static final int MAX_MESSAGE = 256;

    private final GLDatabase db;
    private final WriteQueue queue;
    private final IdCache ids;

    /** table → имя текстовой колонки в этой таблице (определяется один раз по фактической схеме). */
    private final ConcurrentHashMap<String, String> messageColumn = new ConcurrentHashMap<>();

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
        final String message = truncate(e.message());

        queue.submit(conn -> {
            Integer userId = ids.userId(conn, e.userUuid());
            if (userId == null) {
                LOGGER.debug("{}: пропуск — нет пользователя uuid={}", table, e.userUuid());
                return;
            }
            int levelId = ids.levelId(conn, e.levelName());
            String col = messageColumn.computeIfAbsent(table, t -> resolveMessageColumn(conn, t));
            String insSql = "INSERT INTO " + table + "(time, user, level, x, y, z, " + col + ") "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?)";
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

    /**
     * Имя текстовой колонки таблицы по её фактической схеме: предпочитаем {@code message}
     * (схема GLE и {@code chats} GriefLogger), иначе {@code command} (это {@code commands} GriefLogger).
     * Если колонок прочитать не удалось — по умолчанию {@code message} (так таблицу создаёт {@link SchemaMigrator}).
     */
    private String resolveMessageColumn(Connection conn, String table) {
        Set<String> cols = columnNames(conn, table);
        String col = cols.contains("message") ? "message"
                : cols.contains("command") ? "command"
                : "message";
        LOGGER.info("Таблица {}: текстовая колонка = {}.", table, col);
        return col;
    }

    /** Имена колонок таблицы в нижнем регистре. SQLite — через {@code PRAGMA table_info}, MySQL — через metadata. */
    private Set<String> columnNames(Connection conn, String table) {
        Set<String> cols = new HashSet<>();
        try {
            if (db.isMysql()) {
                try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, null)) {
                    while (rs.next()) cols.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            } else {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
                    while (rs.next()) cols.add(rs.getString("name").toLowerCase());
                }
            }
        } catch (SQLException ex) {
            LOGGER.warn("Не удалось прочитать колонки таблицы {}: {}", table, ex.getMessage());
        }
        return cols;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE);
    }
}
