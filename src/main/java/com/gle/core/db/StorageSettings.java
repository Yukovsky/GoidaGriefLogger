package com.gle.core.db;

/**
 * Платформо-нейтральные параметры хранилища GoidaGriefLogger.
 * <p>
 * Часть ЯДРА ({@code com.gle.core.*}) — без импортов NeoForge/Fabric и без обращения к чужим
 * модам. После поглощения GriefLogger (Путь E) мод владеет своей конфигурацией хранилища
 * напрямую: рефлексия-мост к конфигу GL ({@code GLConfigBridge}) удалён. Платформенный слой
 * (NeoForge {@code GLEConfig}) строит этот объект и передаёт в ядро.
 *
 * @param useMysql      использовать MySQL/MariaDB ({@code true}) или SQLite ({@code false})
 * @param mysqlHost     хост MySQL
 * @param mysqlPort     порт MySQL
 * @param mysqlDatabase имя базы MySQL
 * @param mysqlUsername пользователь MySQL
 * @param mysqlPassword пароль MySQL (никогда не логируется)
 * @param mysqlTimeout  таймаут соединения MySQL, мс
 * @param sqliteFile    путь к файлу SQLite (относительно рабочей директории сервера)
 */
public record StorageSettings(
        boolean useMysql,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        String mysqlPassword,
        int mysqlTimeout,
        String sqliteFile
) {
    /** JDBC URL для текущего хранилища. */
    public String jdbcUrl() {
        if (useMysql) {
            return "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase
                    + "?allowReconnect=true&autoReconnect=true&connectTimeout=" + mysqlTimeout;
        }
        return "jdbc:sqlite:" + sqliteFile;
    }
}
