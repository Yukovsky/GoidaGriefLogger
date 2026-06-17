package com.gle.integration;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.core.ItemData;
import com.gle.db.ContainerLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Логирование того, что РЕАЛЬНЫЙ игрок взял/положил через терминал Tom's Simple Storage —
 * в таблицу {@code containers} (REMOVE_ITEM/ADD_ITEM) с UUID игрока, на позиции терминала.
 * <p>
 * Раньше такие перемещения попадали в историю только как {@code [AUTO]} (через универсальную
 * обёртку capability на исходных ящиках) — без имени игрока. Теперь миксин в терминале даёт
 * игрока, а ambient-{@code [AUTO]} на время операции подавляется ({@link AutomationItemLogger}).
 * <p>
 * Тип {@code StoredItemStack} мода читается рефлексией ({@code getStack()}, {@code getQuantity()}),
 * чтобы не зависеть от Tom's на компиляции (как и миксины — {@code require=0}).
 */
public final class TomsTerminalLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/Toms");

    private static volatile Method mGetStack;
    private static volatile Method mGetQuantity;
    private static volatile boolean reflectionFailed;

    private TomsTerminalLogger() {}

    /** Игрок ВЗЯЛ из сети (terminal.pullStack вернул вынутый StoredItemStack) → REMOVE_ITEM. */
    public static void logPull(ServerLevel level, BlockPos pos, Object storedPulled) {
        ServerPlayer player = TomsContext.current();
        if (player == null || storedPulled == null) return;
        ItemStack template = stackOf(storedPulled);
        long qty = quantityOf(storedPulled);
        if (template == null || template.isEmpty() || qty <= 0) return;
        write(level, pos, player, template, clampInt(qty), GLActions.REMOVE_ITEM);
    }

    /**
     * Игрок ПОЛОЖИЛ в сеть. {@code terminal.pushStack(input)} возвращает остаток (или null, если всё
     * влезло); фактически перемещено = кол-во(input) − кол-во(остаток) → ADD_ITEM.
     */
    public static void logPush(ServerLevel level, BlockPos pos, Object storedInput, Object storedRemainder) {
        ServerPlayer player = TomsContext.current();
        if (player == null || storedInput == null) return;
        long moved = quantityOf(storedInput) - (storedRemainder == null ? 0 : quantityOf(storedRemainder));
        if (moved <= 0) return;
        ItemStack template = stackOf(storedInput);
        if (template == null || template.isEmpty()) return;
        write(level, pos, player, template, clampInt(moved), GLActions.ADD_ITEM);
    }

    private static void write(ServerLevel level, BlockPos pos, ServerPlayer player,
                              ItemStack template, int amount, int action) {
        if (!GLEConfig.enableContainerTransactions.get() || !GLStorage.isReady() || amount <= 0) return;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(template.getItem());
        String material = GLMaterials.normalize(key);
        byte[] data;
        try {
            data = ItemData.serialize(template, level.registryAccess());
        } catch (Exception e) {
            data = null;
        }
        GLStorage.get().containers().insert(new ContainerLogDao.ContainerEntry(
                System.currentTimeMillis(),
                player.getUUID().toString(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                material, data, amount, action));
    }

    // --- рефлексия по StoredItemStack ---

    private static ItemStack stackOf(Object stored) {
        if (!ensureReflection(stored)) return null;
        try {
            return (ItemStack) mGetStack.invoke(stored);
        } catch (Exception e) {
            return null;
        }
    }

    private static long quantityOf(Object stored) {
        if (!ensureReflection(stored)) return 0;
        try {
            return (long) mGetQuantity.invoke(stored);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean ensureReflection(Object stored) {
        if (reflectionFailed) return false;
        if (mGetStack != null && mGetQuantity != null) return true;
        try {
            Class<?> c = stored.getClass();
            mGetStack = c.getMethod("getStack");
            mGetQuantity = c.getMethod("getQuantity");
            return true;
        } catch (Exception e) {
            reflectionFailed = true;
            LOGGER.warn("Не удалось привязаться к StoredItemStack Tom's Storage: {}", e.getMessage());
            return false;
        }
    }

    private static int clampInt(long v) {
        return (int) Math.min(v, Integer.MAX_VALUE);
    }
}
