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
 * Simulated (в составе Create Aeronautics): сборка физической структуры и её РАЗБОРКА.
 * <p>
 * Разборка важнее сборки: {@code disassembleSubLevel} принимает целевую позицию, то есть
 * структуру можно собрать в одном месте и высадить в другом — так чужая постройка переезжает
 * целиком. Без пометки потока оба конца этой операции не попадали в лог.
 */
@Mixin(targets = "dev.simulated_team.simulated.util.SimAssemblyHelper", remap = false)
public class SimAssemblyHelperMixin {

    @Inject(method = "assembleFromSingleBlock", at = @At("HEAD"), require = 0, remap = false)
    private static void gle$pushAssemble(CallbackInfoReturnable<Object> cir) {
        GriefContext.push(SourceType.SABLE_ASSEMBLY, SystemUsers.PHYSICS);
    }

    @Inject(method = "assembleFromSingleBlock", at = @At("RETURN"), require = 0, remap = false)
    private static void gle$popAssemble(CallbackInfoReturnable<Object> cir) {
        GriefContext.pop();
    }

    @Inject(method = "disassembleSubLevel", at = @At("HEAD"), require = 0, remap = false)
    private static void gle$pushDisassemble(CallbackInfo ci) {
        GriefContext.push(SourceType.SABLE_DISASSEMBLY, SystemUsers.PHYSICS);
    }

    @Inject(method = "disassembleSubLevel", at = @At("RETURN"), require = 0, remap = false)
    private static void gle$popDisassemble(CallbackInfo ci) {
        GriefContext.pop();
    }
}
