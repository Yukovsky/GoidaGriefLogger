package com.gle.mixin.toms;

import com.gle.GLEConfig;
import com.gle.integration.TomsContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Интеграция Tom's Simple Storage: метим игрока на время обработки взаимодействия с терминалом,
 * чтобы перемещения предметов в/из сети атрибутировались ему (а не {@code [AUTO]}).
 * <p>
 * Точки входа на сервере: {@code receive(CompoundTag)} (клики через сеть терминала → onInteract →
 * te.pull/pushStack) и {@code quickMoveStack} (шифт-клик из инвентаря → te.pushStack). Оборачиваем
 * обе HEAD/RETURN, чтобы контекст жил строго в пределах одного пакета. {@code require=0 / remap=false}
 * — безопасно при отсутствии Tom's. Дочерний CraftingTerminalMenu наследует эти методы (если не
 * переопределяет) и тоже покрыт.
 */
@Mixin(targets = "com.tom.storagemod.menu.StorageTerminalMenu", remap = false)
public abstract class StorageTerminalMenuMixin {

    @Shadow protected Inventory pinv;

    @Inject(method = "receive", at = @At("HEAD"), require = 0, remap = false)
    private void gle$enterReceive(CallbackInfo ci) {
        gle$enter();
    }

    @Inject(method = "receive", at = @At("RETURN"), require = 0, remap = false)
    private void gle$exitReceive(CallbackInfo ci) {
        TomsContext.clear();
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), require = 0, remap = false)
    private void gle$enterQuick(CallbackInfoReturnable<ItemStack> cir) {
        gle$enter();
    }

    @Inject(method = "quickMoveStack", at = @At("RETURN"), require = 0, remap = false)
    private void gle$exitQuick(CallbackInfoReturnable<ItemStack> cir) {
        TomsContext.clear();
    }

    private void gle$enter() {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        if (pinv != null && pinv.player instanceof ServerPlayer sp) {
            TomsContext.set(sp);
        }
    }
}
