package com.gle.mixin;

import com.gle.GLEConfig;
import com.gle.core.GLMaterials;
import com.gle.core.ItemData;
import com.gle.db.GLStorage;
import com.gle.db.GleEventsDao;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * REQ-LOG-007: вставка и поворот предмета в рамке.
 * Инъекция в {@code ItemFrame.interact(Player, InteractionHand)}: на HEAD снимаем предмет/поворот «до»,
 * на RETURN определяем действие (INSERT_ITEM / ROTATE) и пишем в {@code gle_world_entities}.
 * Снятие предмета и слом рамки логируются через AttackEntityEvent в DecorationListener.
 */
@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {

    @Unique private static final ThreadLocal<Object[]> gle$before = new ThreadLocal<>();

    @Inject(method = "interact", at = @At("HEAD"), require = 0)
    private void gle$captureBefore(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!GLEConfig.enableItemFrames.get()) return;
        ItemFrame self = (ItemFrame) (Object) this;
        gle$before.set(new Object[]{ self.getItem().copy(), self.getRotation() });
    }

    @Inject(method = "interact", at = @At("RETURN"), require = 0)
    private void gle$logAfter(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!GLEConfig.enableItemFrames.get()) return;
        Object[] before = gle$before.get();
        gle$before.remove();
        if (before == null || !GLStorage.isReady()) return;

        ItemFrame self = (ItemFrame) (Object) this;
        if (!(self.level() instanceof ServerLevel level)) return;

        ItemStack beforeItem = (ItemStack) before[0];
        int beforeRot = (Integer) before[1];
        ItemStack afterItem = self.getItem();
        int afterRot = self.getRotation();

        String action;
        if (beforeItem.isEmpty() && !afterItem.isEmpty()) {
            action = "INSERT_ITEM";
        } else if (!afterItem.isEmpty() && beforeRot != afterRot) {
            action = "ROTATE";
        } else {
            return; // ничего значимого не изменилось
        }

        String itemName = afterItem.isEmpty() ? null
                : GLMaterials.normalize(BuiltInRegistries.ITEM.getKey(afterItem.getItem()));
        byte[] itemNbt = null;
        if (!afterItem.isEmpty()) {
            try { itemNbt = ItemData.serialize(afterItem, level.registryAccess()); } catch (Exception ignored) {}
        }

        BlockPos pos = self.blockPosition();
        GLStorage.get().events().insertWorldEntity(new GleEventsDao.WorldEntityEntry(
                System.currentTimeMillis(),
                player.getUUID().toString(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(self.getType())),
                self.getUUID().toString(), action,
                itemName, itemNbt, "player",
                "{\"rotation\":" + afterRot + "}"
        ));
    }
}
