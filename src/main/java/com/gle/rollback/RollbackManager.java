package com.gle.rollback;

import com.gle.db.GLStorage;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Управление откатом/restore. ВСЯ работа с БД (выборка, пометка rolled_back, запись job) идёт на
 * выделенном фоновом потоке — главный поток не блокируется. Изменения блоков обязаны выполняться
 * на главном потоке, поэтому применяются пакетами в {@link #tick()} (как в CoreProtect/Ledger).
 */
public final class RollbackManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/RollbackMgr");
    private static final RollbackManager INSTANCE = new RollbackManager();

    public static RollbackManager get() { return INSTANCE; }

    private final List<RollbackJob> active = new CopyOnWriteArrayList<>();
    private final Map<Long, UUID> executorOf = new ConcurrentHashMap<>();
    private final ExecutorService dbExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "GLE-rollback-loader");
                t.setDaemon(true);
                return t;
            });

    private RollbackManager() {}

    /** Тик на главном потоке: применяем пакеты блоков активных заданий. */
    public void tick() {
        if (active.isEmpty()) return;
        for (RollbackJob job : active) {
            if (job.tick()) {
                active.remove(job);
                executorOf.remove(job.jobId());
            }
        }
    }

    public int activeCount() { return active.size(); }

    /** Откат (reverse=true). @return немедленная ошибка валидации или null (дальше — асинхронно). */
    @Nullable
    public String startRollback(MinecraftServer server, UUID executor, String executorName,
                                RollbackFilter filter, Consumer<Component> feedback) {
        return start(server, executor, executorName, filter, feedback, true);
    }

    /** Restore (reverse=false) по тем же фильтрам — повторяет изменения (как /co restore). */
    @Nullable
    public String startRestore(MinecraftServer server, UUID executor, String executorName,
                               RollbackFilter filter, Consumer<Component> feedback) {
        return start(server, executor, executorName, filter, feedback, false);
    }

    @Nullable
    private String start(MinecraftServer server, UUID executor, String executorName,
                         RollbackFilter filter, Consumer<Component> feedback, boolean reverse) {
        if (!GLStorage.isReady()) return "Хранилище недоступно.";

        // «Все миры»: раскрываем в отдельное задание на каждый загруженный мир (движок применяет
        // блоки в один ServerLevel). Пустые миры не шумят сообщениями.
        if (filter.allWorlds) {
            int worlds = 0;
            for (ServerLevel lvl : server.getAllLevels()) {
                RollbackFilter perLevel = filter.copy();
                perLevel.allWorlds = false;
                perLevel.levelName = lvl.dimension().location().toString();
                submit(server, executor, executorName, perLevel, feedback, reverse, lvl, true);
                worlds++;
            }
            feedback.accept(Component.literal("§e[GLE] " + (reverse ? "Откат" : "Restore")
                    + " по всем мирам (" + worlds + ") — миры с совпадениями стартуют отдельно."));
            return null;
        }

        ServerLevel level = resolveLevel(server, filter.levelName);
        if (level == null) return "Неизвестное измерение: " + filter.levelName;
        submit(server, executor, executorName, filter, feedback, reverse, level, false);
        return null;
    }

    private void submit(MinecraftServer server, UUID executor, String executorName,
                        RollbackFilter filter, Consumer<Component> feedback, boolean reverse,
                        ServerLevel level, boolean quietIfEmpty) {
        final String jobType = reverse ? "rollback" : "restore";
        dbExecutor.submit(() -> {
            try {
                List<RollbackData.BlockChange> blocks;
                List<RollbackData.ItemChange> items;
                long jobId;
                try (Connection conn = GLStorage.get().database().newConnection()) {
                    blocks = filter.includeBlocks ? RollbackData.queryBlocks(conn, filter) : List.of();
                    items = filter.includeItems ? RollbackData.queryItems(conn, filter) : List.of();
                    if (blocks.isEmpty() && items.isEmpty()) {
                        if (!quietIfEmpty) server.execute(() -> feedback.accept(Component.literal(
                                "§7Нечего " + (reverse ? "откатывать" : "восстанавливать") + " по фильтрам.")));
                        return;
                    }
                    jobId = RollbackJobsDao.createJob(conn, jobType, null, executor, executorName, filter);
                }
                final long fJobId = jobId;
                final List<RollbackData.BlockChange> fBlocks = blocks;
                final List<RollbackData.ItemChange> fItems = items;
                server.execute(() -> {
                    RollbackJob.Completion done = (status, ab, ac, failed) ->
                            finalizeJob(fJobId, filter, reverse, status, ab, ac, failed);
                    RollbackJob job = new RollbackJob(fJobId, reverse, level, fBlocks, fItems, feedback, done);
                    active.add(job);
                    executorOf.put(fJobId, executor);
                    feedback.accept(Component.literal("§e[GLE] " + (reverse ? "Откат" : "Restore")
                            + " запущен: блоков " + fBlocks.size() + ", записей предметов " + fItems.size()
                            + " (job #" + fJobId + ")."));
                });
            } catch (Exception e) {
                LOGGER.error("Ошибка загрузки данных для {}", jobType, e);
                server.execute(() -> feedback.accept(Component.literal("§c" + translateDbError(e))));
            }
        });
    }

    /** Финализация (вызывается на главном потоке из job.finish) — переносим в фон. */
    private void finalizeJob(long jobId, RollbackFilter filter, boolean reverse,
                             String status, int affectedBlocks, int affectedContainers, int failed) {
        dbExecutor.submit(() -> {
            try (Connection conn = GLStorage.get().database().newConnection()) {
                if ("completed".equals(status)) {
                    int flag = reverse ? 1 : 0; // откат помечает rolled_back=1, restore снимает
                    if (filter.includeBlocks) RollbackData.markBlocksRolledBack(conn, filter, flag);
                    if (filter.includeItems) RollbackData.markContainersRolledBack(conn, filter, flag);
                }
                RollbackJobsDao.finishJob(conn, jobId, status, affectedBlocks, affectedContainers, failed);
            } catch (Exception e) {
                LOGGER.warn("Не удалось финализировать job #{}: {}", jobId, e.getMessage());
            }
        });
    }

    public boolean abort(UUID executor) {
        boolean any = false;
        for (RollbackJob job : active) {
            if (executor.equals(executorOf.get(job.jobId()))) { job.abort(); any = true; }
        }
        return any;
    }

    public static String translateDbError(Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        if (msg.contains("no such table") || msg.contains("doesn't exist") || msg.contains("does not exist")) {
            return "БД не инициализирована или открыт не тот файл: таблицы GriefLogger не найдены. "
                    + "Убедитесь, что GriefLogger создал базу, и не запущен сторонний аддон GLRA со своим конфигом БД "
                    + "(GLE наследует подключение GriefLogger автоматически).";
        }
        if (msg.contains("no such column") || msg.contains("unknown column")) {
            return "В таблицах нет колонок GLE — перезапустите сервер, миграция применится при старте.";
        }
        return "Ошибка БД: " + e.getMessage();
    }

    @Nullable
    private ServerLevel resolveLevel(MinecraftServer server, String levelName) {
        try {
            ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse(levelName));
            return server.getLevel(key);
        } catch (Exception e) {
            return null;
        }
    }
}
