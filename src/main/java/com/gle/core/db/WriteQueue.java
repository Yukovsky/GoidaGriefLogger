package com.gle.core.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Асинхронная очередь записи GLE. Все задачи выполняются на одном выделенном потоке —
 * это сериализует доступ к соединению (которое не потокобезопасно) и снимает нагрузку
 * с игрового потока.
 * <p>
 * При переполнении ({@code asyncQueueSize}) новые задачи отбрасываются с предупреждением
 * (throttling), чтобы не уронить сервер по памяти.
 */
public final class WriteQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/WriteQueue");

    /** Сколько ждём дренажа очереди при остановке, прежде чем прервать поток. */
    private static final long DRAIN_TIMEOUT_MS = 10_000;

    /** Сколько ждать выхода потока из текущего пакета после {@code interrupt()}. */
    private static final long INTERRUPT_GRACE_MS = 2_000;

    /**
     * Максимум задач, сливаемых в одну транзакцию. Каждый коммит на SQLite — это запись в WAL
     * и попытка взять write-lock (конкуренция с GriefLogger). Пакетируя сотни вставок в один
     * коммит, мы сокращаем число коммитов/блокировок в сотни раз — поток успевает разгребать
     * очередь быстрее, чем она наполняется.
     */
    private static final int BATCH_MAX = 512;

    /**
     * Сколько ждать места в очереди, прежде чем отбросить запись. Компромисс: полсекунды тика
     * ради сохранности лога — приемлемо, секунды простоя игрового потока — нет.
     */
    private static final long BACKPRESSURE_MS = 25;

    /**
     * Задача записи. Соединение нужно для разрешения справочных id ({@code IdCache}), а сами
     * строки задача НЕ исполняет: она берёт {@link PreparedStatement} у {@link StatementSink}
     * и делает {@code addBatch()}. Всё, что накопилось за пакет, уходит одним
     * {@code executeBatch()} на таблицу — приём CoreProtect.
     */
    @FunctionalInterface
    public interface WriteTask {
        void run(Connection connection, StatementSink sink) throws SQLException;
    }

    /**
     * Выдаёт {@link PreparedStatement} для SQL в пределах текущего пакета: одинаковый SQL —
     * один и тот же statement, поэтому N строк одной таблицы стоят один prepare и один
     * round-trip вместо N.
     */
    @FunctionalInterface
    public interface StatementSink {
        PreparedStatement statement(String sql) throws SQLException;
    }

    private final GLDatabase db;
    private final BlockingQueue<WriteTask> queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastOverflowWarn = 0;

    /**
     * Хук, вызываемый при ОТКАТЕ пакета (после {@code rollback()}). Нужен, чтобы инвалидировать
     * {@link com.gle.core.db.IdCache}: откат мог отменить вставки в справочники, которые кэш уже
     * запомнил. Держим как {@link Runnable}, чтобы {@code WriteQueue} не зависел от ядра.
     */
    private volatile Runnable onRollback = () -> {};

    public WriteQueue(GLDatabase db, int capacity) {
        this.db = db;
        this.queue = new ArrayBlockingQueue<>(Math.max(256, capacity));
        this.worker = new Thread(this::loop, "GLE-DB-Writer");
        this.worker.setDaemon(true);
    }

    /** Установить хук, вызываемый при откате пакета (например, сброс {@code IdCache}). */
    public void setOnRollback(Runnable onRollback) {
        if (onRollback != null) this.onRollback = onRollback;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            worker.start();
            LOGGER.info("Поток записи GLE запущен (ёмкость очереди {}).", queue.remainingCapacity());
        }
    }

    /**
     * Поставить задачу в очередь.
     * <p>
     * При переполнении раньше запись просто ОТБРАСЫВАЛАСЬ — для мода безопасности это худший
     * из возможных исходов. Теперь даём писателю короткое окно дослить пакет ({@link #BACKPRESSURE_MS}),
     * и только если и это не помогло — отбрасываем со счётчиком. Окно намеренно короткое: это
     * вызывается в том числе с игрового потока, и длинная блокировка обрушила бы TPS.
     */
    public void submit(WriteTask task) {
        if (!running.get()) return;
        if (queue.offer(task)) return;
        try {
            if (queue.offer(task, BACKPRESSURE_MS, TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long n = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastOverflowWarn > 30_000) {
            lastOverflowWarn = now;
            LOGGER.warn("Очередь записи GLE переполнена — отброшено записей: {}. "
                    + "Увеличьте asyncQueueSize или проверьте задержки БД.", n);
        }
    }

    private void loop() {
        final List<WriteTask> batch = new ArrayList<>(BATCH_MAX);
        while (running.get() || !queue.isEmpty()) {
            try {
                WriteTask first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                processBatch(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("Непредвиденная ошибка в потоке записи GLE", e);
                batch.clear();
            }
        }
    }

    /**
     * Обработать пакет: взять соединение, выполнить, а при разрыве соединения (MySQL закрыл
     * сокет по {@code wait_timeout} после простоя) — переподключиться и ПОВТОРИТЬ пакет один раз,
     * чтобы записи не терялись.
     */
    private void processBatch(List<WriteTask> batch) {
        Connection c = db.connection();
        if (c == null) {
            LOGGER.error("Нет соединения GLE — отброшено задач: {}", batch.size());
            return;
        }
        try {
            runBatch(c, batch);
        } catch (SQLException e) {
            if (!isConnectionError(e)) {
                LOGGER.error("Ошибка коммита пакета записи GLE (задач: {})", batch.size(), e);
                return;
            }
            LOGGER.warn("Соединение GLE разорвано ({}) — переподключаемся и повторяем пакет ({} задач).",
                    e.getMessage(), batch.size());
            Connection fresh = db.reconnect();
            if (fresh == null) {
                LOGGER.error("Переподключение GLE не удалось — отброшено задач: {}", batch.size());
                return;
            }
            try {
                runBatch(fresh, batch);
            } catch (SQLException e2) {
                LOGGER.error("Повторная запись пакета GLE не удалась после переподключения (задач: {})",
                        batch.size(), e2);
            }
        }
    }

    /**
     * Выполнить пачку задач в ОДНОЙ транзакции (один коммит). Ошибка отдельной задачи не рушит
     * пакет — она логируется, остальные применяются. Но если ошибка задачи — это разрыв соединения,
     * прерываем весь пакет (его смысла продолжать нет) и пробрасываем наверх для переподключения.
     * Если падает сам коммит (например, SQLITE_BUSY после таймаута) — откатываем пакет и пробрасываем.
     */
    private void runBatch(Connection c, List<WriteTask> batch) throws SQLException {
        boolean restoreAutoCommit = false;
        // LinkedHashMap: статементы исполняются в том порядке, в каком таблицы впервые встретились.
        final Map<String, PreparedStatement> statements = new LinkedHashMap<>();
        final StatementSink sink = sql -> {
            PreparedStatement ps = statements.get(sql);
            if (ps == null) {
                ps = c.prepareStatement(sql);
                statements.put(sql, ps);
            }
            return ps;
        };
        try {
            if (c.getAutoCommit()) {
                c.setAutoCommit(false);
                restoreAutoCommit = true;
            }
            for (WriteTask task : batch) {
                try {
                    task.run(c, sink);
                } catch (SQLException e) {
                    if (isConnectionError(e)) throw e; // соединение мёртвое — прерываем пакет
                    LOGGER.error("Ошибка подготовки задачи записи GLE", e);
                }
            }
            executeAll(statements);
            c.commit();
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // соединение, вероятно, уже нерабочее — переподключение восстановит
            }
            // Пакет откачен: справочные id, закэшированные в этом пакете, могли исчезнуть из БД.
            try {
                onRollback.run();
            } catch (RuntimeException hookEx) {
                LOGGER.warn("Хук отката WriteQueue бросил исключение", hookEx);
            }
            throw e;
        } finally {
            for (PreparedStatement ps : statements.values()) {
                try {
                    ps.close();
                } catch (SQLException ignored) {
                    // закрытие statement'а не должно ронять пакет
                }
            }
            if (restoreAutoCommit) {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // не критично: следующий пакет снова выставит режим
                }
            }
        }
    }

    /**
     * Исполнить накопленные пакеты. Сбой одной таблицы не отменяет остальные: строки логов
     * независимы, и терять весь пакет из-за одной кривой строки нельзя. Разрыв соединения —
     * исключение: он пробрасывается наверх, чтобы пакет целиком повторился после переподключения.
     */
    private void executeAll(Map<String, PreparedStatement> statements) throws SQLException {
        for (Map.Entry<String, PreparedStatement> e : statements.entrySet()) {
            try {
                e.getValue().executeBatch();
            } catch (BatchUpdateException be) {
                if (isConnectionError(be)) throw be;
                int applied = be.getUpdateCounts() == null ? 0 : be.getUpdateCounts().length;
                LOGGER.error("Пакетная вставка GLE не прошла (применено строк: {}), sql: {}",
                        applied, e.getKey(), be);
            } catch (SQLException se) {
                if (isConnectionError(se)) throw se;
                LOGGER.error("Ошибка пакетной вставки GLE, sql: {}", e.getKey(), se);
            }
        }
    }

    /**
     * Разрыв соединения (не транзакционная ошибка БД, а потеря связи): SQLState класса 08
     * ({@code 08S01} «communications link failure», {@code 08003} «connection closed» и т.п.)
     * либо коммуникационное исключение драйвера. Такие ошибки лечатся переподключением.
     */
    private static boolean isConnectionError(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String cn = t.getClass().getName();
            if (cn.contains("CommunicationsException") || cn.contains("CJCommunications")) return true;
            if (t instanceof java.sql.SQLNonTransientConnectionException) return true;
            if (t instanceof java.sql.SQLRecoverableException) return true;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("08");
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int pending() {
        return queue.size();
    }

    /**
     * Остановка с дренажом: снимаем флаг и ДОЖИДАЕМСЯ, пока поток дослит остаток очереди
     * (цикл сам завершится, когда {@code running=false} и очередь пуста). Соединение нельзя
     * закрывать до возврата из этого метода — иначе поток упрётся в закрытый Connection.
     * Прерываем поток только если он не успел дренировать за отведённый таймаут.
     */
    public void stop() {
        running.set(false);
        try {
            worker.join(DRAIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!worker.isAlive()) return;

        int left = queue.size();
        if (left > 0) {
            LOGGER.warn("Поток записи GLE не успел дренировать очередь за {} мс — отброшено записей: {}",
                    DRAIN_TIMEOUT_MS, left);
        }
        worker.interrupt();
        // Дожидаемся выхода из текущего пакета: вызывающий сразу за этим закрывает Connection,
        // и закрытие под незакоммиченной транзакцией потеряло бы уже подготовленный пакет.
        try {
            worker.join(INTERRUPT_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            LOGGER.error("Поток записи GLE не завершился за {} мс после прерывания — "
                    + "соединение закрывается принудительно.", INTERRUPT_GRACE_MS);
        }
    }
}
