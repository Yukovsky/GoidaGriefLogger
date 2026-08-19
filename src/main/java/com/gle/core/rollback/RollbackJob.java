package com.gle.core.rollback;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        /**
         * @param blockIds     id строк {@code blocks}, к которым восстановление РЕАЛЬНО применилось
         * @param containerIds id строк {@code containers}, к которым восстановление РЕАЛЬНО применилось
         */
        void finish(String status, int affectedBlocks, int affectedContainers, int affectedEntities,
                    int failed, List<Long> blockIds, List<Long> containerIds, List<Long> entityIds);
    }

    private final long jobId;
    private final boolean reverse;          // true = rollback, false = restore
    private final ServerLevel level;
    private final List<RollbackData.BlockChange> blocks;
    private final List<RollbackData.ItemChange> items;
    private final List<RollbackData.EntityChange> entities;
    private final Consumer<Component> feedback;
    private final Completion completion;

    private int blockIdx = 0, itemIdx = 0, entityIdx = 0;
    private int affectedBlocks = 0, affectedContainers = 0, affectedEntities = 0, failed = 0;
    private int tickCounter = 0;
    private boolean aborted = false;
    private boolean done = false;

    /**
     * Позиции (asLong), где откат слома контейнера уже восстановил содержимое из NBT-снимка.
     * Для них дельты {@code containers} НЕ применяем — снимок = точное состояние на момент слома,
     * а дельты привели бы к двойному учёту предметов. Все блоки применяются до предметов
     * (второй цикл стартует лишь после исчерпания первого), поэтому к обработке предметов
     * множество уже заполнено.
     */
    private final Set<Long> snapshotRestored = new HashSet<>();

    /**
     * id строк, к которым восстановление реально применилось. Только они помечаются {@code rolled_back}:
     * строка, для которой {@code apply} вернула false (контейнер исчез, блок не резолвится), должна
     * остаться неотмеченной, чтобы повторный откат её добрал.
     */
    private final List<Long> appliedBlockIds = new ArrayList<>();
    private final List<Long> appliedContainerIds = new ArrayList<>();
    private final List<Long> appliedEntityIds = new ArrayList<>();

    /**
     * Причины отказов (без повторов, не больше {@link #MAX_REASONS}). Голое «ошибок 5» ничего
     * не говорит оператору: непонятно, контейнер исчез, места не хватило или блок не опознан.
     */
    private final Set<String> failureReasons = new LinkedHashSet<>();
    private static final int MAX_REASONS = 3;

    private void noteFailure(String why) {
        if (why != null && failureReasons.size() < MAX_REASONS) failureReasons.add(why);
    }

    /** Хвост сообщения с причинами отказов, либо пусто. */
    private String reasonsSuffix() {
        if (failureReasons.isEmpty()) return "";
        String joined = String.join("; ", failureReasons);
        return " §7(" + joined + (failed > failureReasons.size() ? "; …" : "") + ")";
    }

    /**
     * Позиции, которые этот же откат превратил в воздух (реверс PLACE_BLOCK). Дельты
     * {@code containers} по ним применить физически невозможно — контейнера там больше нет.
     * Это не ошибка, а прямое следствие отката, поэтому в счётчик {@code failed} они не идут.
     */
    private final Set<Long> clearedPositions = new HashSet<>();

    public RollbackJob(long jobId, boolean reverse, ServerLevel level,
                       List<RollbackData.BlockChange> blocks, List<RollbackData.ItemChange> items,
                       List<RollbackData.EntityChange> entities,
                       Consumer<Component> feedback, Completion completion) {
        this.jobId = jobId;
        this.reverse = reverse;
        this.level = level;
        this.blocks = blocks;
        this.items = items;
        this.entities = entities;
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
            String why = BlockRestorer.apply(level, ch, reverse);
            if (why == null) {
                affectedBlocks++;
                appliedBlockIds.add(ch.id());
                // Откат слома контейнера со снимком NBT уже точно восстановил его содержимое.
                if (reverse && ch.action() == GLActions.BREAK_BLOCK && ch.nbt() != null) {
                    snapshotRestored.add(new BlockPos(ch.x(), ch.y(), ch.z()).asLong());
                }
                if (reverse && ch.action() == GLActions.PLACE_BLOCK) {
                    clearedPositions.add(new BlockPos(ch.x(), ch.y(), ch.z()).asLong());
                }
            } else { failed++; noteFailure(why); }
            budget--;
        }
        while (budget > 0 && itemIdx < items.size()) {
            RollbackData.ItemChange ch = items.get(itemIdx++);
            budget--;
            // Содержимое этой позиции уже восстановлено снимком блока — дельту не применяем (без двойного учёта).
            long posKey = new BlockPos(ch.x(), ch.y(), ch.z()).asLong();
            if (reverse && snapshotRestored.contains(posKey)) {
                // Содержимое уже восстановлено снимком блока — дельту не применяем, но строку
                // помечаем откатанной: её эффект в мире отменён, повторять его не нужно.
                appliedContainerIds.add(ch.id());
                continue;
            }
            if (reverse && clearedPositions.contains(posKey)) {
                // Контейнер убран этим же откатом — его содержимое больше не имеет смысла.
                appliedContainerIds.add(ch.id());
                continue;
            }
            String why = ItemRestorer.apply(level, ch, reverse);
            if (why == null) {
                affectedContainers++;
                appliedContainerIds.add(ch.id());
            } else { failed++; noteFailure(why); }
        }

        while (budget > 0 && entityIdx < entities.size()) {
            RollbackData.EntityChange ch = entities.get(entityIdx++);
            budget--;
            String why = EntityRestorer.apply(level, ch, reverse);
            if (why == null) {
                affectedEntities++;
                appliedEntityIds.add(ch.id());
            } else { failed++; noteFailure(why); }
        }

        if (++tickCounter % GLEConfig.progressIntervalTicks.get() == 0) {
            feedback.accept(Component.literal("§7[GLE] Прогресс: блоков " + affectedBlocks
                    + ", контейнеров " + affectedContainers
                    + (affectedEntities > 0 ? ", сущностей " + affectedEntities : "")
                    + (failed > 0 ? ", ошибок " + failed : "")));
        }

        if (blockIdx >= blocks.size() && itemIdx >= items.size() && entityIdx >= entities.size()) {
            finish("completed");
            return true;
        }
        return false;
    }

    private void finish(String status) {
        done = true;
        completion.finish(status, affectedBlocks, affectedContainers, affectedEntities, failed,
                List.copyOf(appliedBlockIds), List.copyOf(appliedContainerIds), List.copyOf(appliedEntityIds));
        feedback.accept(Component.literal((reverse ? "§a[GLE] Откат" : "§a[GLE] Restore") + " завершён ("
                + status + "): блоков " + affectedBlocks + ", контейнеров " + affectedContainers
                + (affectedEntities > 0 ? ", сущностей " + affectedEntities : "")
                + (failed > 0 ? ", §cошибок " + failed + reasonsSuffix() : "") + "§a."));
    }
}
