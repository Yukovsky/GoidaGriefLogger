package com.gle.core.db;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.UUID;

/**
 * Сверяет базу с миром, которому она принадлежит.
 * <p>
 * Если карту сбросили, а базу оставили, координаты старых записей указывают в никуда: инспекция
 * покажет чужую историю, а откат по ним изуродует новый мир, поставив блоки там, где их никогда
 * не было. Поэтому при старте метка мира ({@link WorldIdentity}) сверяется с записанной в базе.
 * <p>
 * Автоматически ничего не удаляется. Снести логи по ошибочному срабатыванию хуже, чем показать
 * предупреждение: восстановить их будет неоткуда. Поэтому расхождение только сообщается —
 * решение принимает администратор командой сброса.
 */
public final class WorldGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/WorldGuard");

    /** Результат сверки — читается командой статуса и стартовым предупреждением. */
    public enum State { MATCHED, ADOPTED, MISMATCHED, UNKNOWN }

    private static volatile State state = State.UNKNOWN;
    private static volatile String expected = null;
    private static volatile String actual = null;

    private WorldGuard() {}

    public static State state() { return state; }
    /** Метка, записанная в базе (мир, для которого собраны логи). */
    public static String expected() { return expected; }
    /** Метка текущего мира. */
    public static String actual() { return actual; }

    /** Сверить базу с миром. Вызывается один раз при старте сервера, после инициализации хранилища. */
    public static void verify(MinecraftServer server) {
        if (!GLStorage.isReady()) { state = State.UNKNOWN; return; }
        try {
            UUID worldId = WorldIdentity.of(server).id();
            actual = worldId.toString();
            GLStorage storage = GLStorage.get();
            try (Connection c = storage.database().newConnection()) {
                String stored = GleMetaDao.get(c, GleMetaDao.WORLD_ID);
                if (stored == null) {
                    // Свежая база (или база, заведённая до появления метки) — принимаем текущий мир.
                    GleMetaDao.put(c, storage.isMysql(), GleMetaDao.WORLD_ID, actual);
                    expected = actual;
                    state = State.ADOPTED;
                    LOGGER.info("База закреплена за текущим миром ({}).", actual);
                    return;
                }
                expected = stored;
                state = stored.equals(actual) ? State.MATCHED : State.MISMATCHED;
                if (state == State.MISMATCHED) warn();
            }
        } catch (Exception e) {
            state = State.UNKNOWN;
            LOGGER.warn("Не удалось сверить базу с миром: {}", e.getMessage());
        }
    }

    private static void warn() {
        LOGGER.error("=================================================================");
        LOGGER.error(" МИР НЕ СОВПАДАЕТ С БАЗОЙ ЛОГОВ");
        LOGGER.error(" В базе записан мир {}", expected);
        LOGGER.error(" Сейчас запущен мир   {}", actual);
        LOGGER.error(" Похоже, карту сбросили или подменили, а логи остались от прежней.");
        LOGGER.error(" Их координаты указывают в никуда: инспекция покажет чужую историю,");
        LOGGER.error(" а откат по ним изуродует новый мир.");
        LOGGER.error(" Логирование продолжается — ничего не удалено автоматически.");
        LOGGER.error(" Очистить базу под новый мир: команда 'gl wipe' ИЗ КОНСОЛИ сервера.");
        LOGGER.error("=================================================================");
    }

    /** Закрепить базу за текущим миром — после сброса базы. */
    public static void adopt(MinecraftServer server) {
        try {
            UUID worldId = WorldIdentity.of(server).id();
            GLStorage storage = GLStorage.get();
            try (Connection c = storage.database().newConnection()) {
                GleMetaDao.put(c, storage.isMysql(), GleMetaDao.WORLD_ID, worldId.toString());
            }
            expected = actual = worldId.toString();
            state = State.MATCHED;
        } catch (Exception e) {
            LOGGER.warn("Не удалось закрепить базу за миром: {}", e.getMessage());
        }
    }
}
