package com.gle.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Прозрачный прокси {@link IItemHandler}: делегирует ВСЁ обёрнутому хендлеру, дополнительно
 * наблюдая реальные insert/extract (см. {@link AutomationItemLogger}). Поведение не меняет.
 */
public class GLEItemHandlerWrapper implements IItemHandler {

    protected final IItemHandler wrapped;
    protected final ServerLevel level;
    protected final BlockPos pos;

    public GLEItemHandlerWrapper(IItemHandler wrapped, ServerLevel level, BlockPos pos) {
        this.wrapped = wrapped;
        this.level = level;
        this.pos = pos;
    }

    @Override public int getSlots() { return wrapped.getSlots(); }
    @Override public ItemStack getStackInSlot(int slot) { return wrapped.getStackInSlot(slot); }
    @Override public int getSlotLimit(int slot) { return wrapped.getSlotLimit(slot); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return wrapped.isItemValid(slot, stack); }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        ItemStack remainder = wrapped.insertItem(slot, stack, simulate);
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
        ItemStack result = wrapped.extractItem(slot, amount, simulate);
        if (!simulate && result != null && !result.isEmpty()) {
            try { AutomationItemLogger.logExtract(level, pos, result, result.getCount()); } catch (Throwable ignored) {}
        }
        return result;
    }
}
