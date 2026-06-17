package com.gle.db;

import com.gle.core.db.StorageSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Центральный фасад хранилища GoidaGriefLogger: соединение + миграция + единая очередь записи + DAO.
 * Единственная точка, через которую и игровые, и не-игровые события пишут в БД (единый писатель).
 */
public final class GLStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/Storage");
    private static GLStorage instance;

    private final GLDatabase database;
    private final WriteQueue writeQueue;
    private final BlockLogDao blockDao;
    private final ContainerLogDao containerDao;
    private final GleEventsDao eventsDao;
    private final SessionDao sessionDao;
    private volatile boolean ready;

    /**
     * JVM shutdown hook (Ошибка 3): закрывает очередь/соединение, даже если сервер упал
     * до {@code ServerStoppingEvent} (например, краш во время старта). С единым писателем это
     * единственная точка остановки записи, поэтому дренаж очереди здесь критичен для целостности.
     */
    private static Thread shutdownHook;

    private GLStorage(GLDatabase database, WriteQueue writeQueue) {
        this.database = database;
        this.writeQueue = writeQueue;
        this.blockDao = new BlockLogDao(database, writeQueue);
        this.containerDao = new ContainerLogDao(database, writeQueue);
        this.eventsDao = new GleEventsDao(database, writeQueue);
        this.sessionDao = new SessionDao(database, writeQueue);
    }

    /** Инициализация при старте сервера. Возвращает true, если хранилище готово к записи. */
    public static synchronized boolean init(StorageSettings settings, int asyncQueueSize) {
        if (instance != null && instance.ready) return true;

        if (settings == null) {
            LOGGER.error("Нет конфигурации хранилища — запись отключена.");
            return false;
        }
        GLDatabase db = new GLDatabase(settings);
        if (!db.connect()) {
            LOGGER.error("Не удалось подключиться к хранилищу — запись отключена.");
            return false;
        }
        WriteQueue queue = new WriteQueue(db, asyncQueueSize);
        queue.start();

        GLStorage storage = new GLStorage(db, queue);
        new SchemaMigrator(db).migrate();
        storage.ready = true;
        instance = storage;
        registerShutdownHook();
        LOGGER.info("Хранилище GoidaGriefLogger инициализировано (mysql={}).", db.isMysql());
        return true;
    }

    private static void registerShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = new Thread(() -> {
            // Не дёргаем Runtime.removeShutdownHook отсюда — мы уже в процессе остановки JVM.
            if (instance != null) {
                LOGGER.warn("JVM завершается — аварийно дренируем очередь записи.");
                doShutdown(false);
            }
        }, "GoidaGriefLogger-Shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static GLStorage get() {
        return instance;
    }

    public static boolean isReady() {
        return instance != null && instance.ready && instance.database.isAvailable();
    }

    public BlockLogDao blocks() {
        return blockDao;
    }

    public ContainerLogDao containers() {
        return containerDao;
    }

    public GleEventsDao events() {
        return eventsDao;
    }

    public SessionDao sessions() {
        return sessionDao;
    }

    public GLDatabase database() {
        return database;
    }

    public WriteQueue queue() {
        return writeQueue;
    }

    public boolean isMysql() {
        return database.isMysql();
    }

    public static synchronized void shutdown() {
        doShutdown(true);
    }

    private static synchronized void doShutdown(boolean removeHook) {
        if (instance == null) return;
        instance.ready = false;
        instance.writeQueue.stop();
        instance.database.close();
        if (removeHook && shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM уже останавливается — снять хук нельзя, не страшно.
            }
            shutdownHook = null;
        }
        instance = null;
        LOGGER.info("Хранилище GoidaGriefLogger остановлено.");
    }
}
