package com.gle.integration.toms.mixin;

import com.gle.integration.toms.TomsContext;
import com.gle.integration.toms.TomsTerminalLogger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Интеграция Tom's Simple Storage: фиксируем, что игрок взял/положил через терминал.
 * {@code pullStack} (взять из сети) и {@code pushStack(StoredItemStack)} (положить в сеть) —
 * единственные точки изменения сети из меню. Логируем только когда активен {@link TomsContext}
 * (т.е. это действие реального игрока, а не автоматики). {@code require=0 / remap=false} — безопасно
 * без Tom's. {@code StoredItemStack} берём как {@code Object} (рефлексия в логгере).
 */
@Mixin(targets = "com.tom.storagemod.block.entity.StorageTerminalBlockEntity", remap = false)
public abstract class StorageTerminalBlockEntityMixin {

    @Inject(method = "pullStack(Lcom/tom/storagemod/inventory/StoredItemStack;J)Lcom/tom/storagemod/inventory/StoredItemStack;",
            at = @At("RETURN"), require = 0, remap = false)
    private void gle$onPull(CallbackInfoReturnable<Object> cir) {
        if (!TomsContext.isActive()) return;
        Object pulled = cir.getReturnValue();
        if (pulled == null) return;
        BlockEntity be = (BlockEntity) (Object) this;
        if (be.getLevel() instanceof ServerLevel sl) {
            TomsTerminalLogger.logPull(sl, be.getBlockPos(), pulled);
        }
    }

    @Inject(method = "pushStack(Lcom/tom/storagemod/inventory/StoredItemStack;)Lcom/tom/storagemod/inventory/StoredItemStack;",
            at = @At("RETURN"), require = 0, remap = false)
    private void gle$onPush(@Coerce Object input, CallbackInfoReturnable<Object> cir) {
        if (!TomsContext.isActive() || input == null) return;
        BlockEntity be = (BlockEntity) (Object) this;
        if (be.getLevel() instanceof ServerLevel sl) {
            TomsTerminalLogger.logPush(sl, be.getBlockPos(), input, cir.getReturnValue());
        }
    }
}
