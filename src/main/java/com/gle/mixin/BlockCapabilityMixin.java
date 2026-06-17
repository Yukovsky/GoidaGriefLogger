package com.gle.mixin;

import com.gle.GLEConfig;
import com.gle.integration.AutomationItemLogger;
import com.gle.integration.GLEItemHandlerWrapper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Универсальный перехват предметов (опция {@code universalItemTracking}). Оборачивает результат
 * разрешения capability {@code Capabilities.ItemHandler.BLOCK}, чтобы наблюдать любое движение
 * предметов между инвентарями (Create logistics, Tom's Storage, Vibrant Vaults, Contraption
 * Terminals, и т.д.). remap=false: BlockCapability — класс NeoForge (Mojang-маппинги в проде).
 */
@Mixin(BlockCapability.class)
public class BlockCapabilityMixin {

    @Inject(
            method = "getCapability(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/lang/Object;)Ljava/lang/Object;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false
    )
    private void gle$wrapItemHandler(Level level, BlockPos pos, BlockState state, BlockEntity be, Object context,
                                     CallbackInfoReturnable<Object> cir) {
        if (!GLEConfig.universalItemTracking.get()) return;
        if ((Object) this != Capabilities.ItemHandler.BLOCK) return;
        Object value = cir.getReturnValue();
        if (!(value instanceof IItemHandler handler)) return;
        if (value instanceof GLEItemHandlerWrapper) return; // уже обёрнут
        if (!(level instanceof ServerLevel sl)) return;
        cir.setReturnValue(AutomationItemLogger.wrap(handler, sl, pos.immutable()));
    }
}
