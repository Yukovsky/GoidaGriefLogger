package com.gle.integration.sable.mixin;

import com.gle.core.GriefContext;
import com.gle.core.SourceType;
import com.gle.core.SystemUsers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable: сборка физической структуры из блоков мира и её перемещение.
 * <p>
 * Блоки вынимаются из мира прямым {@code setBlock} — событий нет. Без атрибуции
 * {@code LevelChunkMixin} уходит в {@code EnvironmentLogger}, который знает только огонь, лаву,
 * воду, лёд и скалк, — и целая постройка, поднятая в физическую структуру, исчезала из лога
 * бесследно. Здесь мы лишь помечаем поток, а сами изменения пишет общий grief-путь.
 * <p>
 * Эта же точка покрывает Create Aeronautics и Simulated: они миксинят в этот самый класс.
 * <p>String-target + {@code remap=false} + {@code require=0}: нет Sable — миксин не применяется.
 */
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
public class SubLevelAssemblyHelperMixin {

    @Inject(method = "assembleBlocks", at = @At("HEAD"), require = 0, remap = false)
    private static void gle$pushAssemble(CallbackInfoReturnable<Object> cir) {
        GriefContext.push(SourceType.SABLE_ASSEMBLY, SystemUsers.PHYSICS);
    }

    @Inject(method = "assembleBlocks", at = @At("RETURN"), require = 0, remap = false)
    private static void gle$popAssemble(CallbackInfoReturnable<Object> cir) {
        GriefContext.pop();
    }

    @Inject(method = "moveBlocks", at = @At("HEAD"), require = 0, remap = false)
    private static void gle$pushMove(CallbackInfo ci) {
        GriefContext.push(SourceType.SABLE_MOVE, SystemUsers.PHYSICS);
    }

    @Inject(method = "moveBlocks", at = @At("RETURN"), require = 0, remap = false)
    private static void gle$popMove(CallbackInfo ci) {
        GriefContext.pop();
    }
}
