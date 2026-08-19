package com.gle.core.db;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище NBT-снимков, адресуемое СОДЕРЖИМЫМ: одинаковые снимки лежат в БД один раз,
 * а события ссылаются на них числовым id.
 * <p>
 * Зачем. NBT — самая тяжёлая часть лога. Убийства мобов дают поток событий, и хранить у каждого
 * полный снимок означало бы раздувать базу кратно. После отсечения изменчивых полей
 * (позиция, UUID, скорость — см. {@code EntityNbt}) снимок обычного зомби совпадает у всех
 * обычных зомби, поэтому дедупликация по содержимому убирает практически весь объём, оставляя
 * ровно то, что действительно уникально: имена, экипировку, изменённые атрибуты.
 * <p>
 * Кэш {@code hash → id} работает так же, как {@link IdCache}: живёт на единственном потоке
 * записи, попадание отдаёт готовый id без обращения к БД.
 */
public final class NbtStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/NbtStore");

    private final boolean mysql;
    private final Map<String, Integer> byHash = new ConcurrentHashMap<>();

    public NbtStore(boolean mysql) {
        this.mysql = mysql;
    }

    /**
     * id снимка, вставляя его при первом появлении.
     *
     * @return {@code null} для пустого снимка — «нечего хранить» это валидное состояние
     *         и отдельной строки не заслуживает.
     */
    @Nullable
    public Integer idFor(Connection c, byte @Nullable [] data) throws SQLException {
        if (data == null || data.length == 0) return null;
        String hash = sha256(data);
        Integer cached = byHash.get(hash);
        if (cached != null) return cached;

        String ignore = mysql ? "INSERT IGNORE" : "INSERT OR IGNORE";
        try (PreparedStatement ins = c.prepareStatement(
                ignore + " INTO gle_nbt(hash, size, data) VALUES(?, ?, ?)")) {
            ins.setString(1, hash);
            ins.setInt(2, data.length);
            ins.setBytes(3, data);
            ins.executeUpdate();
        }
        try (PreparedStatement sel = c.prepareStatement("SELECT id FROM gle_nbt WHERE hash = ?")) {
            sel.setString(1, hash);
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) {
                    LOGGER.warn("Не удалось получить id NBT-снимка после вставки (hash={})", hash);
                    return null;
                }
                int id = rs.getInt(1);
                byHash.put(hash, id);
                return id;
            }
        }
    }

    /** Прочитать снимок по id. Используется откатом. */
    public static byte @Nullable [] load(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT data FROM gle_nbt WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** Сброс кэша при откате пакета записи — как у {@link IdCache}. */
    public void clear() {
        byHash.clear();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
