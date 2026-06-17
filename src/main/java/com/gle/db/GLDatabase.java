package com.gle.db;

import com.gle.core.db.StorageSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Соединение GoidaGriefLogger с хранилищем мода (единственный писатель, Путь E).
 * Параметры берутся из собственной конфигурации ({@link StorageSettings}); рефлексия-мост к
 * конфигу GriefLogger удалён — мод стал standalone.
 * <ul>
 *   <li><b>SQLite</b>: {@code jdbc:sqlite:<sqliteFile>} + {@code PRAGMA journal_mode=WAL} и
 *       {@code busy_timeout}. С единым писателем конкуренции за файл больше нет, но WAL остаётся
 *       полезным для читающих соединений роллбека.</li>
 *   <li><b>MySQL/MariaDB</b>: один коннект к БД мода.</li>
 * </ul>
 * Все записи идут через {@link WriteQueue} на единственном потоке, поэтому соединение
 * используется однопоточно.
 */
public final class GLDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/Database");

    private final StorageSettings config;
    private Connection connection;
    private volatile boolean mysql;

    public GLDatabase(StorageSettings config) {
        this.config = config;
    }

    public boolean isMysql() {
        return mysql;
    }

    public synchronized boolean connect() {
        if (config == null) {
            LOGGER.error("Нет конфигурации хранилища — GoidaGriefLogger отключает запись.");
            return false;
        }
        try {
            if (config.useMysql()) {
                mysql = true;
                openMysql();
            } else {
                mysql = false;
                openSqlite();
            }
            return connection != null && !connection.isClosed();
        } catch (Exception e) {
            LOGGER.error("Не удалось подключиться к хранилищу", e);
            return false;
        }
    }

    private void openSqlite() throws Exception {
        connection = newConnection();
        LOGGER.info("Подключён к SQLite ({}, WAL).", config.sqliteFile());
    }

    private void openMysql() throws Exception {
        connection = newConnection();
        // Пароль НЕ логируем (требование безопасности §15).
        LOGGER.info("Подключён к MySQL ({}:{}/{}).",
                config.mysqlHost(), config.mysqlPort(), config.mysqlDatabase());
    }

    /**
     * Создать новое независимое соединение с тем же хранилищем.
     * Используется роллбеком для чтения, чтобы не конкурировать с потоком записи.
     */
    public Connection newConnection() throws Exception {
        Connection conn;
        if (config.useMysql()) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            conn = DriverManager.getConnection(config.jdbcUrl(), config.mysqlUsername(), config.mysqlPassword());
            conn.setAutoCommit(true);
        } else {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection(config.jdbcUrl());
            conn.setAutoCommit(true);
            // PRAGMA не должны быть фатальными: соединение всё равно рабочее даже без WAL.
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA busy_timeout=5000;");
                st.execute("PRAGMA synchronous=NORMAL;");
                // foreign_keys НЕ включаем: GriefLogger тоже оставлял их выключенными в SQLite
                // (FK объявлены, но не форсируются), и порядок вставок на это рассчитан.
            } catch (SQLException e) {
                LOGGER.warn("Не удалось применить PRAGMA SQLite (продолжаем без WAL): {}", e.getMessage());
            }
        }
        return conn;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Переоткрыть соединение после разрыва (например, MySQL закрыл сокет по {@code wait_timeout}
     * после простоя). Закрывает мёртвый коннект и создаёт новый по той же конфигурации.
     * Вызывается из потока записи при {@code CommunicationsException}.
     *
     * @return живое соединение, либо {@code null}, если переподключиться не удалось.
     */
    public synchronized Connection reconnect() {
        closeQuietly();
        if (connect()) {
            LOGGER.info("Соединение переустановлено.");
            return connection;
        }
        return null;
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // соединение, вероятно, уже разорвано — игнорируем
            }
            connection = null;
        }
    }

    public boolean isAvailable() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.warn("Ошибка при закрытии соединения", e);
            }
            connection = null;
        }
    }
}
