package com.gle.core.db;

import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Служебные пары «ключ — значение» базы ({@code gle_meta}): то, что относится к базе целиком,
 * а не к отдельному событию. Сейчас это метка мира, которому принадлежат логи.
 */
public final class GleMetaDao {

    /** Метка мира, для которого собрана эта база. */
    public static final String WORLD_ID = "world_id";

    private GleMetaDao() {}

    @Nullable
    public static String get(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM gle_meta WHERE key_name = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static void put(Connection c, boolean mysql, String key, String value) throws SQLException {
        String sql = mysql
                ? "INSERT INTO gle_meta(key_name, value) VALUES(?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)"
                : "INSERT INTO gle_meta(key_name, value) VALUES(?, ?) "
                        + "ON CONFLICT(key_name) DO UPDATE SET value = excluded.value";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
