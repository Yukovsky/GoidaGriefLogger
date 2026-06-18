package com.gle.core.rollback;

import com.gle.GLEConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.function.Consumer;

/**
 * Одно задание отката/restore. Тикается на главном потоке пакетами {@code batchSize}
 * (изменения блоков обязаны идти в главном потоке). НИКАКОГО доступа к БД во время тика —
 * выборка делается асинхронно до старта, а финализация (флаг rolled_back, запись job) —
 * асинхронно после завершения через {@link Completion}.
 */
public final class RollbackJob {

    /** Колбэк финализации (выполняется асинхронно вне главного потока). */
    @FunctionalInterface
    public interface Completion {
        void finish(String status, int affectedBlocks, int affectedContainers, int failed);
    }

    private final long jobId;
    private final boolean reverse;          // true = rollback, false = restore
    private final ServerLevel level;
    private final List<RollbackData.BlockChange> blocks;
    private final List<RollbackData.ItemChange> items;
    private final Consumer<Component> feedback;
    private final Completion completion;

    private int blockIdx = 0, itemIdx = 0;
    private int affectedBlocks = 0, affectedContainers = 0, failed = 0;
    private int tickCounter = 0;
    private boolean aborted = false;
    private boolean done = false;

    public RollbackJob(long jobId, boolean reverse, ServerLevel level,
                       List<RollbackData.BlockChange> blocks, List<RollbackData.ItemChange> items,
                       Consumer<Component> feedback, Completion completion) {
        this.jobId = jobId;
        this.reverse = reverse;
        this.level = level;
        this.blocks = blocks;
        this.items = items;
        this.feedback = feedback;
        this.completion = completion;
    }

    public long jobId() { return jobId; }
    public boolean isDone() { return done; }
    public void abort() { aborted = true; }

    /** Один тик. @return true если задание завершилось. */
    public boolean tick() {
        if (done) return true;
        if (aborted) { finish("aborted"); return true; }

        int budget = GLEConfig.batchSize.get();
        while (budget > 0 && blockIdx < blocks.size()) {
            RollbackData.BlockChange ch = blocks.get(blockIdx++);
            if (BlockRestorer.apply(level, ch, reverse)) affectedBlocks++; else failed++;
            budget--;
        }
        while (budget > 0 && itemIdx < items.size()) {
            RollbackData.ItemChange ch = items.get(itemIdx++);
            if (ItemRestorer.apply(level, ch, reverse)) affectedContainers++; else failed++;
            budget--;
        }

        if (++tickCounter % GLEConfig.progressIntervalTicks.get() == 0) {
            feedback.accept(Component.literal("§7[GLE] Прогресс: блоков " + affectedBlocks
                    + ", контейнеров " + affectedContainers + (failed > 0 ? ", ошибок " + failed : "")));
        }

        if (blockIdx >= blocks.size() && itemIdx >= items.size()) {
            finish("completed");
            return true;
        }
        return false;
    }

    private void finish(String status) {
        done = true;
        completion.finish(status, affectedBlocks, affectedContainers, failed);
        feedback.accept(Component.literal((reverse ? "§a[GLE] Откат" : "§a[GLE] Restore") + " завершён ("
                + status + "): блоков " + affectedBlocks + ", контейнеров " + affectedContainers
                + (failed > 0 ? ", §cошибок " + failed : "") + "§a."));
    }
}
