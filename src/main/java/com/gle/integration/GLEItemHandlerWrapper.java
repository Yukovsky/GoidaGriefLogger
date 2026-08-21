package com.gle.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Прозрачный прокси {@link IItemHandler}: делегирует ВСЁ обёрнутому хендлеру, дополнительно
 * наблюдая реальные insert/extract (см. {@link AutomationItemLogger}).
 * <p>
 * На happy path поведение не меняется: возвращается ровно то, что вернул обёрнутый хендлер.
 * Дополнительно каждый делегирующий вызов защищён от СБОЯ АПСТРИМА и возвращает нейтральный
 * дефолт вместо того, чтобы пробросить исключение наружу. Так бывает, когда capability уже
 * «протухла» — например, блок сломан или чанк выгружен, пока сторонний код (Jade и подобные
 * тултипы) асинхронно опрашивает его инвентарь; тогда чужой {@code InvWrapper} падает NPE
 * внутри себя. Прокси стоит на пути практически ЛЮБОГО обращения к item-хендлеру блока
 * (опция {@code universalItemTracking}), поэтому такой сбой обязан оставаться грациозным
 * отказом на границе прокси, а не крашем вызывающего кода.
 */
public class GLEItemHandlerWrapper implements IItemHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/ItemHandler");

    protected final IItemHandler wrapped;
    protected final ServerLevel level;
    protected final BlockPos pos;

    public GLEItemHandlerWrapper(IItemHandler wrapped, ServerLevel level, BlockPos pos) {
        this.wrapped = wrapped;
        this.level = level;
        this.pos = pos;
    }

    @Override
    public int getSlots() {
        try {
            return wrapped.getSlots();
        } catch (Throwable t) {
            upstreamFailed("getSlots", t);
            return 0;
        }
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        try {
            return wrapped.getStackInSlot(slot);
        } catch (Throwable t) {
            upstreamFailed("getStackInSlot", t);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        try {
            return wrapped.getSlotLimit(slot);
        } catch (Throwable t) {
            upstreamFailed("getSlotLimit", t);
            return 0;
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        try {
            return wrapped.isItemValid(slot, stack);
        } catch (Throwable t) {
            upstreamFailed("isItemValid", t);
            return false;
        }
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        ItemStack remainder;
        try {
            remainder = wrapped.insertItem(slot, stack, simulate);
        } catch (Throwable t) {
            upstreamFailed("insertItem", t);
            return stack; // вставки не произошло — возвращаем стак целиком, логировать нечего
        }
        if (!simulate && !stack.isEmpty()) {
            int moved = stack.getCount() - (remainder == null ? 0 : remainder.getCount());
            if (moved > 0) {
                try { AutomationItemLogger.logInsert(level, pos, stack, moved); } catch (Throwable ignored) {}
            }
        }
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack result;
        try {
            result = wrapped.extractItem(slot, amount, simulate);
        } catch (Throwable t) {
            upstreamFailed("extractItem", t);
            return ItemStack.EMPTY; // извлечения не произошло — логировать нечего
        }
        if (!simulate && result != null && !result.isEmpty()) {
            try { AutomationItemLogger.logExtract(level, pos, result, result.getCount()); } catch (Throwable ignored) {}
        }
        return result;
    }

    /**
     * Только debug: это ожидаемая гонка, а не авария, и при включённом {@code universalItemTracking}
     * на сервере с сотнями блоков в тик уровень выше debug превратился бы в спам.
     */
    private void upstreamFailed(String method, Throwable t) {
        LOGGER.debug("Обёрнутый хендлер упал на {} в {}; возвращён нейтральный результат", method, pos, t);
    }
}
