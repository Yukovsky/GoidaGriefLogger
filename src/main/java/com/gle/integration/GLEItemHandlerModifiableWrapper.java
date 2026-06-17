package com.gle.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Версия {@link GLEItemHandlerWrapper} для {@link IItemHandlerModifiable} — сохраняет «форму»
 * обёрнутого хендлера, чтобы вызывающие, кастующие к Modifiable, не ломались.
 */
public class GLEItemHandlerModifiableWrapper extends GLEItemHandlerWrapper implements IItemHandlerModifiable {

    private final IItemHandlerModifiable modifiable;

    public GLEItemHandlerModifiableWrapper(IItemHandlerModifiable wrapped, ServerLevel level, BlockPos pos) {
        super(wrapped, level, pos);
        this.modifiable = wrapped;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        modifiable.setStackInSlot(slot, stack);
    }
}
