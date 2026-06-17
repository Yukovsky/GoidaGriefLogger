package com.gle.mixin;

import com.gle.core.ActivationLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jetbrains.annotations.Nullable;

/**
 * Нажимные плиты (каменные/деревянные/взвешенные). GriefLogger их активацию не пишет
 * (срабатывают наступанием, а не right-click). Инъекция в {@code checkPressed}: логируем
 * переход «не нажата → нажата» с сущностью-инициатором. Снятие сигнала (тик, entity=null)
 * не логируем. {@code require=0} — при несовпадении маппинга фича просто не активна.
 */
@Mixin(BasePressurePlateBlock.class)
public abstract class BasePressurePlateBlockMixin {

    @Shadow protected abstract int getSignalStrength(Level level, BlockPos pos);

    @Inject(method = "checkPressed", at = @At("HEAD"), require = 0)
    private void gle$onCheckPressed(@Nullable Entity entity, Level level, BlockPos pos, BlockState state,
                                    int currentSignal, CallbackInfo ci) {
        if (entity == null) return;
        int now = getSignalStrength(level, pos);
        if (now > 0 && currentSignal == 0) { // переход в нажатое состояние
            ActivationLogger.logActivation(level, pos, state, entity);
        }
    }
}
