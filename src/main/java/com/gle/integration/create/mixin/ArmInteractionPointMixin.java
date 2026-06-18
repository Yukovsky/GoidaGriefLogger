package com.gle.integration.create.mixin;

import com.gle.integration.create.CreateItemLogger;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Интеграция Create: Mechanical Arm (REQ-LOG-MOD-003). Рука перемещает предметы через
 * {@code ArmInteractionPoint.insert/extract} — у точки известна позиция контейнера (vault, сундук
 * и т.д.). Логируем реальные (не simulate) переносы. {@code @Local} (MixinExtras) достаёт аргументы
 * без ссылки на типы Create. require=0 + remap=false — безопасно при отсутствии Create.
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint", remap = false)
public abstract class ArmInteractionPointMixin {

    @Shadow public abstract BlockPos getPos();
    @Shadow public abstract Level getLevel();

    @Inject(method = "insert", at = @At("RETURN"), require = 0, remap = false)
    private void gle$onInsert(CallbackInfoReturnable<ItemStack> cir,
                              @Local(argsOnly = true) ItemStack stack,
                              @Local(argsOnly = true) boolean simulate) {
        if (simulate || stack == null || stack.isEmpty()) return;
        ItemStack remainder = cir.getReturnValue();
        int moved = stack.getCount() - (remainder == null ? 0 : remainder.getCount());
        if (moved > 0 && getLevel() instanceof ServerLevel sl) {
            CreateItemLogger.log(sl, getPos(), stack, moved, true);
        }
    }

    @Inject(method = "extract", at = @At("RETURN"), require = 0, remap = false)
    private void gle$onExtract(CallbackInfoReturnable<ItemStack> cir,
                               @Local(argsOnly = true) boolean simulate) {
        if (simulate) return;
        ItemStack extracted = cir.getReturnValue();
        if (extracted != null && !extracted.isEmpty() && getLevel() instanceof ServerLevel sl) {
            CreateItemLogger.log(sl, getPos(), extracted, extracted.getCount(), false);
        }
    }
}
