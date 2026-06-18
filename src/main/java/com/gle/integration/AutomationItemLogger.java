package com.gle.integration;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.ItemLogger;
import com.gle.core.SystemUsers;
import com.gle.integration.toms.TomsContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Универсальное логирование перемещений предметов через {@code IItemHandler} (опция
 * {@code universalItemTracking}). Оборачивает хендлер блока прозрачным прокси, который только
 * НАБЛЮДАЕТ реальные (не simulate) insert/extract и пишет их как пользователь {@code [AUTO]}.
 * Покрывает воронки/ленты/жёлоба/насосы Create, Tom's Simple Storage, Create Vibrant Vaults,
 * Create Contraption Terminals и любые моды-автоматизации без модозависимых миксинов.
 */
public final class AutomationItemLogger {

    private AutomationItemLogger() {}

    private static final ConcurrentHashMap<Long, Long> RECENT = new ConcurrentHashMap<>();

    /** Включено ли универсальное отслеживание (используется хопперами/рукой для дедупа). */
    public static boolean enabled() {
        return GLEConfig.universalItemTracking.get();
    }

    /** Обернуть хендлер, сохранив его «форму» (modifiable или нет). */
    public static IItemHandler wrap(IItemHandler handler, ServerLevel level, BlockPos pos) {
        if (handler instanceof IItemHandlerModifiable m) {
            return new GLEItemHandlerModifiableWrapper(m, level, pos);
        }
        return new GLEItemHandlerWrapper(handler, level, pos);
    }

    static void logInsert(ServerLevel level, BlockPos pos, ItemStack inserted, int amount) {
        if (!enabled() || amount <= 0 || inserted.isEmpty()) return;
        if (TomsContext.isActive()) return; // действие игрока через терминал Tom's логируем поимённо
        if (dup(pos, inserted, true)) return;
        ItemLogger.log(level, pos, inserted, amount, GLActions.ADD_ITEM, "automation", SystemUsers.AUTO);
    }

    static void logExtract(ServerLevel level, BlockPos pos, ItemStack extracted, int amount) {
        if (!enabled() || amount <= 0 || extracted.isEmpty()) return;
        if (TomsContext.isActive()) return; // действие игрока через терминал Tom's логируем поимённо
        if (dup(pos, extracted, false)) return;
        ItemLogger.log(level, pos, extracted, amount, GLActions.REMOVE_ITEM, "automation", SystemUsers.AUTO);
    }

    private static boolean dup(BlockPos pos, ItemStack stack, boolean add) {
        int window = GLEConfig.deduplicationWindowMs.get();
        if (window <= 0) return false;
        long now = System.currentTimeMillis();
        long key = pos.asLong() * 31 + stack.getItem().hashCode() * 2L + (add ? 1 : 0);
        Long last = RECENT.put(key, now);
        if (RECENT.size() > 8192) RECENT.entrySet().removeIf(e -> now - e.getValue() > window * 4L);
        return last != null && (now - last) < window;
    }
}
