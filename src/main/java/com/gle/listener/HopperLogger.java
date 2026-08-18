package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.ItemLogger;
import com.gle.core.SourceType;
import com.gle.core.SystemUsers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * REQ-LOG-003: логирование переносов предметов хоппером.
 * Вызывается из {@link com.gle.mixin.HopperBlockEntityMixin} после успешного
 * {@code HopperBlockEntity.addItem(source, destination, stack, dir)}.
 * <p>
 * Пишет {@code REMOVE_ITEM} из позиции источника и {@code ADD_ITEM} в позицию назначения
 * (когда контейнеры являются BlockEntity и позиция известна). Дедупликация по
 * {@code (from, to, item)} в окне {@code deduplicationWindowMs} гасит повторные тики.
 */
public final class HopperLogger {

    private HopperLogger() {}

    private static final ConcurrentHashMap<String, Long> RECENT = new ConcurrentHashMap<>();
    private static long lastPrune = 0;

    /** Вызывается из миксина: предмет {@code movedSnapshot} (с реально перенесённым количеством). */
    public static void onTransfer(@Nullable Container source, Container destination, ItemStack movedSnapshot) {
        if (!GLEConfig.enableHoppers.get()) return;
        // если включён универсальный перехват предметов — он покроет хопперы (не дублируем)
        if (com.gle.integration.AutomationItemLogger.enabled()) return;
        if (movedSnapshot.isEmpty()) return;

        BlockPos fromPos = posOf(source);
        BlockPos toPos = posOf(destination);
        ServerLevel level = serverLevelOf(source);
        if (level == null) level = serverLevelOf(destination);
        if (level == null) return;

        if (isDuplicate(fromPos, toPos, movedSnapshot)) return;

        int amount = movedSnapshot.getCount();
        if (fromPos != null) {
            ItemLogger.log(level, fromPos, movedSnapshot, amount,
                    GLActions.REMOVE_ITEM, SourceType.HOPPER, SystemUsers.HOPPER);
        }
        if (toPos != null) {
            ItemLogger.log(level, toPos, movedSnapshot, amount,
                    GLActions.ADD_ITEM, SourceType.HOPPER, SystemUsers.HOPPER);
        }
    }

    @Nullable
    private static BlockPos posOf(@Nullable Container container) {
        return container instanceof BlockEntity be ? be.getBlockPos() : null;
    }

    @Nullable
    private static ServerLevel serverLevelOf(@Nullable Container container) {
        if (container instanceof BlockEntity be && be.getLevel() instanceof ServerLevel sl) {
            return sl;
        }
        return null;
    }

    private static boolean isDuplicate(@Nullable BlockPos from, @Nullable BlockPos to, ItemStack stack) {
        int window = GLEConfig.deduplicationWindowMs.get();
        if (window <= 0) return false;
        long now = System.currentTimeMillis();
        String key = (from == null ? "?" : from.asLong()) + ">" + (to == null ? "?" : to.asLong())
                + ":" + stack.getItem() + "x" + stack.getCount();
        Long last = RECENT.put(key, now);
        // Очистка по ВРЕМЕНИ, а не только при переполнении: ниже порога 4096 ключи раньше
        // жили до конца жизни процесса.
        if (now - lastPrune > 30_000L || RECENT.size() > 4096) {
            lastPrune = now;
            RECENT.entrySet().removeIf(e -> now - e.getValue() > Math.max(window * 4L, 1000L));
        }
        return last != null && (now - last) < window;
    }
}
