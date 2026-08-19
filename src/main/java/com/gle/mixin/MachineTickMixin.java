package com.gle.mixin;

import com.gle.core.MachineActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Печь/коптильня/доменная печь отчитываются о том, что израсходовали и произвели САМИ.
 * Без этого их работа при открытом GUI попадала в разницу контейнера и записывалась на игрока
 * (см. {@link MachineActivity}).
 * <p>
 * Учёт ведётся только пока контейнер кем-то открыт, поэтому обычный тик печи ничего не стоит:
 * на горячем пути это одна проверка карты. {@code require=0} — при несовпадении маппинга
 * фича просто неактивна, сервер не падает.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class MachineTickMixin {

    private static final ThreadLocal<Map<String, Integer>> GLE$BEFORE = new ThreadLocal<>();

    @Inject(method = "serverTick", at = @At("HEAD"), require = 0)
    private static void gle$tickHead(Level level, BlockPos pos, BlockState state,
                                     AbstractFurnaceBlockEntity be, CallbackInfo ci) {
        if (!MachineActivity.isTracked(pos)) return;
        GLE$BEFORE.set(MachineActivity.snapshot((Container) be, level.registryAccess()));
    }

    @Inject(method = "serverTick", at = @At("RETURN"), require = 0)
    private static void gle$tickReturn(Level level, BlockPos pos, BlockState state,
                                       AbstractFurnaceBlockEntity be, CallbackInfo ci) {
        Map<String, Integer> before = GLE$BEFORE.get();
        if (before == null) return;
        GLE$BEFORE.remove();
        MachineActivity.record(pos, before, MachineActivity.snapshot((Container) be, level.registryAccess()));
    }
}
