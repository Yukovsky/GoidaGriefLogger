package com.gle.mixin;

import com.gle.core.GLActions;
import com.gle.core.ItemLogger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Бросок и выстрел предметов игроком — игровое событие, которое раньше писал GriefLogger
 * (ItemAction THROW=7 / SHOOT=8). После поглощения GL (Путь E) пишет единый writer GoidaGriefLogger.
 * <p>
 * Перехватываем {@code Projectile.shootFromRotation}: {@link ThrowableItemProjectile} (снежки,
 * яйца, жемчуг Края, всплеск-зелья) — это бросок; {@link AbstractArrow} (стрелы) — выстрел.
 * Порт {@code ProjectileMixin} из GriefLogger (Apache-2.0).
 */
@Mixin(Projectile.class)
public class ProjectileMixin {

    @Inject(at = @At("HEAD"),
            method = "shootFromRotation(Lnet/minecraft/world/entity/Entity;FFFFF)V")
    private void goida$logProjectile(Entity entity, float f, float g, float h, float i, float j, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        Projectile self = (Projectile) (Object) this;
        if (self instanceof ThrowableItemProjectile throwable) {
            ItemLogger.logPlayerItem(player, throwable.getItem(), GLActions.THROW_ITEM);
        } else if (self instanceof AbstractArrow arrow) {
            ItemStack pickup = ((AbstractArrowAccessor) (Object) arrow).goida$getPickupItem();
            ItemLogger.logPlayerItem(player, pickup, GLActions.SHOOT_ITEM);
        }
    }
}
