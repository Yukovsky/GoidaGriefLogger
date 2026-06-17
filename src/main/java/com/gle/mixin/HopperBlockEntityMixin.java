package com.gle.mixin;

import com.gle.GLEConfig;
import com.gle.listener.HopperLogger;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * REQ-LOG-003: перехват переноса предметов хоппером.
 * Инъекция в {@code HopperBlockEntity.addItem(Container source, Container destination, ItemStack stack, Direction)}
 * — единый метод вансимиллы, через который предмет реально переходит между контейнерами
 * (используется и при выталкивании, и при засасывании).
 * <p>
 * На HEAD запоминаем исходный стак, на RETURN считаем перенесённое количество
 * (исходное − остаток) и передаём в {@link HopperLogger}. {@code require=0} — при несовпадении
 * маппинга сервер не падает.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Unique
    private static final ThreadLocal<ItemStack> gle$original = new ThreadLocal<>();

    @Inject(
            method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), require = 0
    )
    private static void gle$captureBefore(@Nullable Container source, Container destination,
                                          ItemStack stack, @Nullable Direction direction,
                                          CallbackInfoReturnable<ItemStack> cir) {
        if (!GLEConfig.enableHoppers.get()) return;
        gle$original.set(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
    }

    @Inject(
            method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"), require = 0
    )
    private static void gle$logAfter(@Nullable Container source, Container destination,
                                     ItemStack stack, @Nullable Direction direction,
                                     CallbackInfoReturnable<ItemStack> cir) {
        if (!GLEConfig.enableHoppers.get()) return;
        ItemStack orig = gle$original.get();
        gle$original.remove();
        if (orig == null || orig.isEmpty()) return;

        ItemStack leftover = cir.getReturnValue();
        int moved = orig.getCount() - (leftover == null ? 0 : leftover.getCount());
        if (moved <= 0) return;

        HopperLogger.onTransfer(source, destination, orig.copyWithCount(moved));
    }
}
