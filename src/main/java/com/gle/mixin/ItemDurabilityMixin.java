package com.gle.mixin;

import com.gle.core.GLActions;
import com.gle.core.ItemLogger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Поломка предмета от износа (прочность дошла до нуля) — игровое событие, которое раньше писал
 * GriefLogger (ItemAction BREAK=5). После поглощения GL (Путь E) пишет единый writer.
 * <p>
 * Перехватываем {@code ItemStack.hurtAndBreak} на HEAD и ПРЕДСКАЗЫВАЕМ поломку тем же расчётом,
 * что и сам метод (с учётом «Прочности»), чтобы записать предмет до его уничтожения.
 * Порт {@code MixinItemStack} из GriefLogger (Apache-2.0).
 */
@Mixin(ItemStack.class)
public class ItemDurabilityMixin {

    @Inject(at = @At("HEAD"),
            method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V")
    private void goida$logBreak(int amount, ServerLevel level, ServerPlayer player,
                                Consumer<Item> onBroken, CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (!self.isDamageableItem()) return;
        if (player != null && (player.hasInfiniteMaterials() || player instanceof FakePlayer)) return;

        int delta = amount;
        if (delta > 0) {
            delta = EnchantmentHelper.processDurabilityChange(level, self, delta);
            if (delta <= 0) return;
        }
        if (self.getDamageValue() + delta >= self.getMaxDamage() && player != null) {
            ItemLogger.logPlayerItem(player, self.copyWithCount(1), GLActions.BREAK_ITEM);
        }
    }
}
