package com.gle.mixin;

import com.gle.core.ActivationLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Тропвайр (натянутая нить): GriefLogger его срабатывание не пишет. Инъекция в
 * {@code entityInside}: если нить ещё не под сигналом ({@code !POWERED}) и в неё вошла
 * сущность — логируем активацию с инициатором. {@code require=0} — мягкий no-op при
 * несовпадении маппинга.
 */
@Mixin(TripWireBlock.class)
public abstract class TripWireBlockMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), require = 0)
    private void gle$onEntityInside(BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (level.isClientSide) return;
        if (!state.getValue(BlockStateProperties.POWERED)) {
            ActivationLogger.logActivation(level, pos, state, entity);
        }
    }
}
