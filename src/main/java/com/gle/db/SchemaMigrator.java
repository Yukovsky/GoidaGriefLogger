package com.gle.db;

import com.gle.core.SystemUsers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * Миграция схемы под нужды GLE поверх существующей БД GriefLogger.
 * <ol>
 *   <li>Добавляет колонки в {@code blocks}: source_type, source_player_uuid, extra_data, block_nbt, nbt_truncated.</li>
 *   <li>Создаёт собственные таблицы GLE ({@code gle_signs}, {@code gle_world_entities}, {@code gle_player_deaths})
 *       и таблицы заданий роллбека ({@code rollback_jobs}, {@code rollback_job_blocks}).</li>
 *   <li>Заводит системных пользователей ([PISTON], [HOPPER], ...).</li>
 * </ol>
 * Запускается ПОСЛЕ того как GriefLogger создал свои таблицы (на ServerStarted).
 */
public final class SchemaMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/Schema");

    private final GLDatabase db;

    public SchemaMigrator(GLDatabase db) {
        this.db = db;
    }

    public void migrate() {
        Connection c = db.connection();
        if (c == null) return;
        try {
            createBaseTables(c);   // Путь E: мод сам владеет базовой схемой (GriefLogger удалён)
            addBlockColumns(c);
            createGleTables(c);
            createLookupIndexes(c);
            insertSystemUsers(c);
            LOGGER.info("Миграция схемы GoidaGriefLogger завершена.");
        } catch (SQLException e) {
            LOGGER.error("Ошибка миграции схемы GoidaGriefLogger", e);
        }
    }

    // --- 0. Базовые таблицы (унаследованы от GriefLogger, теперь создаёт сам мод) ----------
    //
    // DDL перенесён из репозиториев GriefLogger (Apache-2.0) дословно, чтобы существующая БД GL
    // оставалась полностью совместимой (CREATE TABLE IF NOT EXISTS не трогает уже созданные).
    // FK сохранены как у GL: в SQLite они объявлены, но не форсируются (PRAGMA foreign_keys off);
    // в MySQL/InnoDB форсируются — с ЕДИНЫМ писателем это уже не вызывает кросс-транзакционных
    // дедлоков (см. docs/06 §1). Снятие FK ради нагрузки — отдельный шаг Фазы 2.

    private void createBaseTables(Connection c) throws SQLException {
        boolean my = db.isMysql();
        String engine = my ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4" : "";
        // Справочники (FK-родители)
        if (my) {
            exec(c, "CREATE TABLE IF NOT EXISTS users (id int PRIMARY KEY AUTO_INCREMENT, name varchar(16) NOT NULL, uuid varchar(36) DEFAULT NULL UNIQUE)" + engine);
            exec(c, "CREATE TABLE IF NOT EXISTS levels (id int PRIMARY KEY AUTO_INCREMENT, name varchar(256) NOT NULL UNIQUE)" + engine);
            exec(c, "CREATE TABLE IF NOT EXISTS materials (id int PRIMARY KEY AUTO_INCREMENT, name varchar(256) NOT NULL UNIQUE)" + engine);
            exec(c, "CREATE TABLE IF NOT EXISTS entities (id int PRIMARY KEY AUTO_INCREMENT, name varchar(256) NOT NULL UNIQUE)" + engine);
            exec(c, "CREATE TABLE IF NOT EXISTS usernames (id int PRIMARY KEY AUTO_INCREMENT, time bigint NOT NULL, uuid varchar(36) NOT NULL, name varchar(16) NOT NULL, UNIQUE(uuid, name))" + engine);
        } else {
            exec(c, "CREATE TABLE IF NOT EXISTS users (id integer PRIMARY KEY, name text NOT NULL, uuid text DEFAULT NULL UNIQUE)");
            exec(c, "CREATE TABLE IF NOT EXISTS levels (id integer PRIMARY KEY, name text NOT NULL UNIQUE)");
            exec(c, "CREATE TABLE IF NOT EXISTS materials (id integer PRIMARY KEY, name text NOT NULL UNIQUE)");
            exec(c, "CREATE TABLE IF NOT EXISTS entities (id integer PRIMARY KEY, name text NOT NULL UNIQUE)");
            exec(c, "CREATE TABLE IF NOT EXISTS usernames (id integer PRIMARY KEY, time integer NOT NULL, uuid text NOT NULL, name text NOT NULL, UNIQUE(uuid, name))");
        }
        // Горячие таблицы
        String tInt  = my ? "int" : "integer";
        String tTime = my ? "bigint" : "integer";
        exec(c, "CREATE TABLE IF NOT EXISTS blocks (time " + tTime + " NOT NULL, user " + tInt + " NOT NULL, level " + tInt + " NOT NULL, "
                + "x " + tInt + " NOT NULL, y " + tInt + " NOT NULL, z " + tInt + " NOT NULL, type " + tInt + " NOT NULL, action " + tInt + " NOT NULL, "
                + "FOREIGN KEY(user) REFERENCES users(id), FOREIGN KEY(level) REFERENCES levels(id), FOREIGN KEY(type) REFERENCES materials(id))" + engine);
        exec(c, "CREATE TABLE IF NOT EXISTS containers (time " + tTime + " NOT NULL, user " + tInt + " NOT NULL, level " + tInt + " NOT NULL, "
                + "x " + tInt + " NOT NULL, y " + tInt + " NOT NULL, z " + tInt + " NOT NULL, type " + tInt + " NOT NULL, data blob DEFAULT NULL, amount " + tInt + " NOT NULL, action " + tInt + " NOT NULL, "
                + "FOREIGN KEY(user) REFERENCES users(id), FOREIGN KEY(level) REFERENCES levels(id), FOREIGN KEY(type) REFERENCES materials(id))" + engine);
        exec(c, "CREATE TABLE IF NOT EXISTS items (time " + tTime + " NOT NULL, user " + tInt + " NOT NULL, level " + tInt + " NOT NULL, "
                + "x " + tInt + " NOT NULL, y " + tInt + " NOT NULL, z " + tInt + " NOT NULL, type " + tInt + " NOT NULL, data blob DEFAULT NULL, amount " + tInt + " NOT NULL, action " + tInt + " NOT NULL, "
                + "FOREIGN KEY(user) REFERENCES users(id), FOREIGN KEY(level) REFERENCES levels(id), FOREIGN KEY(type) REFERENCES materials(id))" + engine);
        exec(c, "CREATE TABLE IF NOT EXISTS sessions (time " + tTime + " NOT NULL, user " + tInt + " NOT NULL, level " + tInt + " NOT NULL, "
                + "x " + tInt + " NOT NULL, y " + tInt + " NOT NULL, z " + tInt + " NOT NULL, action " + tInt + " NOT NULL, "
                + "FOREIGN KEY(user) REFERENCES users(id), FOREIGN KEY(level) REFERENCES levels(id))" + engine);
        String tMsg = my ? "varchar(256)" : "text";
        exec(c, "CREATE TABLE IF NOT EXISTS chats (time " + tTime + " NOT NULL, user " + tInt + " NOT NULL, level " + tInt + " NOT NULL, "
                + "x " + tInt + " NOT NULL, y " + tInt + " NOT NULL, z " + tInt + " NOT NULL, message " + tMsg + " NOT NULL, "
                + "FOREIGN KEY(user) REFERENCES users(id), FOREIGN KEY(level) REFERENCES levels(id))" + engine);
        exec(c, "CREATE TABLE IF NOT EXISTS commands (time " + tTime + " NOT NULL, user " + tInt + " NOT NULL, level " + tInt + " NOT NULL, "
                + "x " + tInt + " NOT NULL, y " + tInt + " NOT NULL, z " + tInt + " NOT NULL, message " + tMsg + " NOT NULL, "
                + "FOREIGN KEY(user) REFERENCES users(id), FOREIGN KEY(level) REFERENCES levels(id))" + engine);
        LOGGER.info("Базовая схема обеспечена (11 таблиц).");
    }

    // --- 1. Колонки в blocks ---------------------------------------------------

    private void addBlockColumns(Connection c) throws SQLException {
        String nbtType = db.isMysql() ? "MEDIUMBLOB" : "BLOB";
        addColumnIfMissing(c, "blocks", "source_type", "TEXT");
        addColumnIfMissing(c, "blocks", "source_player_uuid", "TEXT");
        addColumnIfMissing(c, "blocks", "extra_data", "TEXT");
        addColumnIfMissing(c, "blocks", "block_nbt", nbtType);
        addColumnIfMissing(c, "blocks", "nbt_truncated", "INTEGER DEFAULT 0");
        // Пометка отката (как в CoreProtect): 1 = строка откатана и в lookup показывается зачёркнутой.
        addColumnIfMissing(c, "blocks", "rolled_back", "INTEGER DEFAULT 0");
        addColumnIfMissing(c, "containers", "rolled_back", "INTEGER DEFAULT 0");
        execQuiet(c, "CREATE INDEX " + (db.isMysql() ? "" : "IF NOT EXISTS ")
                + "idx_blocks_source ON blocks(source_type)");
    }

    private void addColumnIfMissing(Connection c, String table, String column, String type) throws SQLException {
        if (columnExists(c, table, column)) return;
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            LOGGER.info("blocks: добавлена колонка {} {}", column, type);
        } catch (SQLException e) {
            // Возможна гонка с другой инстанцией / уже добавлено — не фатально.
            LOGGER.warn("Не удалось добавить колонку {}.{} ({})", table, column, e.getMessage());
        }
    }

    private boolean columnExists(Connection c, String table, String column) {
        try {
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, column)) {
                if (rs.next()) return true;
            }
            // SQLite иногда чувствителен к регистру таблицы — пробуем PRAGMA как fallback.
            if (!db.isMysql()) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
                    while (rs.next()) {
                        if (column.equalsIgnoreCase(rs.getString("name"))) return true;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Проверка колонки {}.{} не удалась: {}", table, column, e.getMessage());
        }
        return false;
    }

    // --- 2. Собственные таблицы GLE -------------------------------------------

    private void createGleTables(Connection c) throws SQLException {
        String pk  = db.isMysql() ? "INT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY";
        String blob = db.isMysql() ? "MEDIUMBLOB" : "BLOB";
        String i   = db.isMysql() ? "INT" : "INTEGER";
        String big = db.isMysql() ? "BIGINT" : "INTEGER";
        String txt = db.isMysql() ? "TEXT" : "TEXT";

        exec(c, "CREATE TABLE IF NOT EXISTS gle_signs (" +
                "id " + pk + ", time " + big + " NOT NULL, user " + i + ", level " + i + " NOT NULL, " +
                "x " + i + ", y " + i + ", z " + i + ", " +
                "front_before " + txt + ", back_before " + txt + ", front_after " + txt + ", back_after " + txt + ", " +
                "flags " + txt + ")");

        exec(c, "CREATE TABLE IF NOT EXISTS gle_world_entities (" +
                "id " + pk + ", time " + big + " NOT NULL, user " + i + ", level " + i + " NOT NULL, " +
                "x " + i + ", y " + i + ", z " + i + ", " +
                "entity_type " + txt + ", entity_uuid " + txt + ", action " + txt + ", " +
                "item " + txt + ", item_nbt " + blob + ", source_type " + txt + ", extra_data " + txt + ")");

        // NBT-снимок содержимого контейнера на момент слома — в т.ч. для сломов ИГРОКОМ,
        // которые логирует сам GriefLogger (без block_nbt). Нужен, чтобы откат вернул контейнер
        // С предметами (а не пустым). См. RollbackData enrich.
        exec(c, "CREATE TABLE IF NOT EXISTS gle_block_nbt (" +
                "id " + pk + ", time " + big + " NOT NULL, level " + txt + " NOT NULL, " +
                "x " + i + ", y " + i + ", z " + i + ", nbt " + blob + ")");
        execQuiet(c, "CREATE INDEX " + (db.isMysql() ? "" : "IF NOT EXISTS ")
                + "idx_gle_nbt_pos ON gle_block_nbt(level, x, y, z, time)");

        exec(c, "CREATE TABLE IF NOT EXISTS gle_player_deaths (" +
                "id " + pk + ", time " + big + " NOT NULL, player_uuid " + txt + " NOT NULL, " +
                "level " + i + ", x " + i + ", y " + i + ", z " + i + ", " +
                "cause " + txt + ", inventory_nbt " + blob + ", restored " + i + " DEFAULT 0)");

        exec(c, "CREATE TABLE IF NOT EXISTS rollback_jobs (" +
                "id " + pk + ", job_type " + txt + " NOT NULL, parent_job_id " + i + ", " +
                "started_at " + big + " NOT NULL, completed_at " + big + ", " +
                "executor_uuid " + txt + " NOT NULL, executor_name " + txt + " NOT NULL, " +
                "filter_time_from " + big + ", filter_time_to " + big + ", " +
                "filter_player_uuid " + txt + ", filter_player_name " + txt + ", " +
                "filter_radius DOUBLE, filter_cx " + i + ", filter_cy " + i + ", filter_cz " + i + ", filter_level " + txt + ", " +
                "filter_include_blocks " + i + " DEFAULT 1, filter_include_items " + i + " DEFAULT 1, " +
                "affected_blocks " + i + " DEFAULT 0, affected_containers " + i + " DEFAULT 0, failed_count " + i + " DEFAULT 0, " +
                "status " + txt + " NOT NULL)");

        exec(c, "CREATE TABLE IF NOT EXISTS rollback_job_blocks (" +
                "id " + pk + ", job_id " + i + " NOT NULL, " +
                "level " + txt + ", x " + i + ", y " + i + ", z " + i + ", " +
                "pre_rollback_state " + txt + ", pre_rollback_nbt " + blob + ", " +
                "applied_state " + txt + ", applied_nbt " + blob + ", success " + i + " DEFAULT 0)");

        execQuiet(c, "CREATE INDEX " + (db.isMysql() ? "" : "IF NOT EXISTS ") + "idx_rjb_job ON rollback_job_blocks(job_id)");
        execQuiet(c, "CREATE INDEX " + (db.isMysql() ? "" : "IF NOT EXISTS ") + "idx_rj_exec ON rollback_jobs(executor_uuid, started_at)");
        execQuiet(c, "CREATE INDEX " + (db.isMysql() ? "" : "IF NOT EXISTS ") + "idx_rj_status ON rollback_jobs(status, started_at)");
    }

    // --- 2b. Индексы для /gl search -------------------------------------------

    /**
     * Индексы, ускоряющие lookup ({@code /gl search}) и rollback по таблицам GriefLogger.
     * GL для своих таблиц spatial/time-индексов не создаёт, поэтому каждый поиск делал полный
     * скан blocks/containers/items. Добавляем:
     * <ul>
     *   <li>{@code (level, x, z)} — для радиусных запросов (узкий бокс по координатам);</li>
     *   <li>{@code (time)} — для запросов «во всех мирах»/по времени (ORDER BY time DESC LIMIT).</li>
     * </ul>
     * Создаются один раз; стоимость на запись окупается за счёт батч-коммитов {@link WriteQueue}.
     */
    private void createLookupIndexes(Connection c) {
        for (String t : new String[]{"blocks", "containers", "items"}) {
            createIndex(c, "idx_" + t + "_pos",  t, "level, x, z");
            createIndex(c, "idx_" + t + "_time", t, "time");
        }
        createIndex(c, "idx_sessions_pos", "sessions", "level, x, z");
        createIndex(c, "idx_gle_signs_pos",   "gle_signs",   "level, x, z");
        createIndex(c, "idx_gle_we_pos",      "gle_world_entities", "level, x, z");
        createIndex(c, "idx_gle_deaths_pos",  "gle_player_deaths",  "level, x, z");
    }

    private void createIndex(Connection c, String name, String table, String columns) {
        execQuiet(c, "CREATE INDEX " + (db.isMysql() ? "" : "IF NOT EXISTS ")
                + name + " ON " + table + "(" + columns + ")");
    }

    // --- 3. Системные пользователи --------------------------------------------

    private void insertSystemUsers(Connection c) throws SQLException {
        String sql = db.isMysql()
                ? "INSERT IGNORE INTO users(name, uuid) VALUES(?, ?)"
                : "INSERT OR IGNORE INTO users(name, uuid) VALUES(?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, String> e : SystemUsers.ALL.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        LOGGER.info("Системные пользователи GLE обеспечены ({} шт.).", SystemUsers.ALL.size());
    }

    // --- helpers ---------------------------------------------------------------

    private void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private void execQuiet(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            LOGGER.debug("(ожидаемо) {}: {}", sql, e.getMessage());
        }
    }
}
