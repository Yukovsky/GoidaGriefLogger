package com.gle.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Доступ к защищённому {@code AbstractArrow.getPickupItem()} (предмет, который даёт стрела при
 * подборе) для логирования выстрела в {@link ProjectileMixin}. В маппинге Parchment метод
 * {@code protected}, поэтому вызываем через {@code @Invoker}.
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {

    @Invoker("getPickupItem")
    ItemStack goida$getPickupItem();
}
