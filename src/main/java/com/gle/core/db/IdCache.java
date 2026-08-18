package com.gle.core.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.SQLException;

/**
 * In-memory кэши {@code name → id} для справочников {@code materials}/{@code levels}/{@code entities}
 * и {@code uuid → id} для {@code users} (приём CoreProtect, см. docs/06 §2, §6 — Фаза 2).
 * <p>
 * До этого каждое событие делало {@code INSERT IGNORE} в справочник плюс подзапрос
 * {@code (SELECT id FROM ... WHERE ...)} в горячей вставке. На UNIQUE-колонке {@code name}
 * дубль-вставка берёт gap/next-key локи — это и есть главный очаг конкуренции за блокировки
 * (корневая причина краха при двух писателях). С единым писателем дедлоков уже нет, но
 * {@code INSERT IGNORE} на КАЖДОЕ событие — лишняя работа и WAL-запись.
 * <p>
 * Кэш превращает это в «вставку в справочник только при ПЕРВОМ появлении имени»: попадание в кэш
 * отдаёт готовый int-id без обращения к БД, а горячая вставка кладёт этот id напрямую — без
 * {@code INSERT IGNORE} и без подзапросов.
 * <p>
 * <b>Потокобезопасность.</b> Кэш используется исключительно из единственного потока записи
 * {@code WriteQueue} (внутри задач {@code queue.submit(...)}), поэтому гонок нет;
 * {@link ConcurrentHashMap} взят как дешёвая страховка на случай чтения из иного потока.
 * <p>
 * <b>Стабильность id.</b> Идентификаторы справочников — это auto-increment PK, они никогда
 * не меняются, поэтому кэш не нужно инвалидировать при переподключении соединения
 * ({@code GLDatabase.reconnect()}): тот же файл/БД отдаёт те же id.
 * <p>
 * <b>Промахи по {@code users} НЕ кэшируются.</b> Пользователь может появиться позже (вход игрока
 * через {@code SessionDao}), поэтому отрицательный результат поиска по uuid не запоминается —
 * иначе мы бы навсегда «запомнили», что игрока нет.
 */
public final class IdCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/IdCache");

    private final boolean mysql;

    private final Map<String, Integer> materials = new ConcurrentHashMap<>();
    private final Map<String, Integer> levels    = new ConcurrentHashMap<>();
    private final Map<String, Integer> entities   = new ConcurrentHashMap<>();
    private final Map<String, Integer> users      = new ConcurrentHashMap<>(); // uuid → id

    public IdCache(boolean mysql) {
        this.mysql = mysql;
    }

    private String ignore() {
        return mysql ? "INSERT IGNORE" : "INSERT OR IGNORE";
    }

    // --- Справочники по имени (всегда возвращают валидный id: на промахе вставляем) ---

    /** id материала по нормализованному имени (без {@code minecraft:}); вставляет при первом появлении. */
    public int materialId(Connection c, String name) throws SQLException {
        return dictId(c, materials, "materials", name);
    }

    /** id мира по имени; вставляет при первом появлении. */
    public int levelId(Connection c, String name) throws SQLException {
        return dictId(c, levels, "levels", name);
    }

    /** id сущности по имени (с префиксом {@code minecraft:}, как у GriefLogger); вставляет при первом появлении. */
    public int entityId(Connection c, String name) throws SQLException {
        return dictId(c, entities, "entities", name);
    }

    private int dictId(Connection c, Map<String, Integer> cache, String table, String name) throws SQLException {
        Integer cached = cache.get(name);
        if (cached != null) return cached;

        // Первое появление имени: гарантируем строку справочника, затем читаем её id.
        try (PreparedStatement ins = c.prepareStatement(ignore() + " INTO " + table + "(name) VALUES(?)")) {
            ins.setString(1, name);
            ins.executeUpdate();
        }
        Integer id = selectId(c, "SELECT id FROM " + table + " WHERE name = ?", name);
        if (id == null) {
            // Не должно случаться (мы только что вставили), но не кэшируем мусор.
            throw new SQLException("Не удалось получить id для " + table + ".name=" + name);
        }
        cache.put(name, id);
        return id;
    }

    // --- Пользователи по uuid -------------------------------------------------

    /**
     * id пользователя по uuid без создания строки. Возвращает {@code null}, если пользователя ещё нет
     * (например, событие до записи входа игрока) — вызывающий решает, что делать (для NOT NULL
     * колонок — пропустить запись, как делал подзапрос GriefLogger, дававший NULL).
     * Промахи не кэшируются.
     */
    public Integer userId(Connection c, String uuid) throws SQLException {
        if (uuid == null) return null;
        Integer cached = users.get(uuid);
        if (cached != null) return cached;
        Integer id = selectId(c, "SELECT id FROM users WHERE uuid = ?", uuid);
        if (id != null) users.put(uuid, id);
        return id;
    }

    /**
     * id пользователя по uuid, создавая строку при отсутствии.
     * <p>
     * Раньше горячие DAO звали {@link #userId} и при {@code null} МОЛЧА выбрасывали событие: строку
     * {@code users} создавал только вход игрока через {@code SessionDao}. Любой uuid вне «системные
     * юзеры + залогинившиеся игроки» терял события без следа. CoreProtect в такой ситуации всегда
     * заводит пользователя — делаем так же.
     * <p>
     * Имя: для системных uuid берётся из {@link com.gle.core.SystemUsers}, иначе ставится временная
     * метка из префикса uuid — вход игрока починит её через {@link #upsertUser}.
     */
    public Integer userIdOrCreate(Connection c, String uuid) throws SQLException {
        if (uuid == null) return null;
        Integer id = userId(c, uuid);
        if (id != null) return id;
        String known = com.gle.core.SystemUsers.nameOf(uuid);
        // users.name — varchar(16), поэтому плейсхолдер обязан быть коротким.
        String name = known != null ? known : "?" + uuid.substring(0, Math.min(8, uuid.length()));
        return upsertUser(c, uuid, name);
    }

    /**
     * Гарантировать пользователя ({@code INSERT IGNORE users(name, uuid)}) и вернуть его id.
     * Используется при входе игрока ({@code SessionDao}). История имён ({@code usernames}) — забота
     * вызывающего, кэш её не трогает.
     */
    public Integer upsertUser(Connection c, String uuid, String name) throws SQLException {
        if (uuid == null) return null;
        Integer cached = users.get(uuid);
        if (cached != null) {
            // Кэш-попадание не гарантирует верное ИМЯ: строку мог завести userIdOrCreate с плейсхолдером.
            repairName(c, uuid, name);
            return cached;
        }
        try (PreparedStatement ins = c.prepareStatement(ignore() + " INTO users(name, uuid) VALUES(?, ?)")) {
            ins.setString(1, name);
            ins.setString(2, uuid);
            ins.executeUpdate();
        }
        repairName(c, uuid, name);
        Integer id = selectId(c, "SELECT id FROM users WHERE uuid = ?", uuid);
        if (id != null) users.put(uuid, id);
        else LOGGER.warn("Не удалось получить id пользователя после вставки (uuid={})", uuid);
        return id;
    }

    /**
     * Заменить временное имя ({@code ?xxxxxxxx}, поставленное {@link #userIdOrCreate}) на настоящее.
     * No-op на уровне БД, если имя уже верное. Вызывается только при входе игрока, не на горячем пути.
     */
    private void repairName(Connection c, String uuid, String name) throws SQLException {
        if (name == null || name.startsWith("?")) return;
        try (PreparedStatement up = c.prepareStatement("UPDATE users SET name = ? WHERE uuid = ? AND name <> ?")) {
            up.setString(1, name);
            up.setString(2, uuid);
            up.setString(3, name);
            up.executeUpdate();
        }
    }

    /**
     * Сбросить все кэши. Вызывается при ОТКАТЕ пакета записи ({@code WriteQueue}): откаченная
     * транзакция могла отменить {@code INSERT} в справочник, который мы уже успели закэшировать
     * в этом же пакете — тогда кэш указывал бы на несуществующую строку и FK-ссылка из горячей
     * вставки повисла бы. Полный сброс безопасен: уже закоммиченные строки справочников просто
     * перечитаются (один лишний {@code SELECT} на имя), новые — перевставятся.
     */
    public void clear() {
        materials.clear();
        levels.clear();
        entities.clear();
        users.clear();
    }

    private static Integer selectId(Connection c, String sql, String key) throws SQLException {
        try (PreparedStatement sel = c.prepareStatement(sql)) {
            sel.setString(1, key);
            try (ResultSet rs = sel.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }
}
