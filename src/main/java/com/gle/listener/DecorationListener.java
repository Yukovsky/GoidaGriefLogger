package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLMaterials;
import com.gle.core.ItemData;
import com.gle.core.SystemUsers;
import com.gle.core.db.GLStorage;
import com.gle.core.db.GleEventsDao;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import org.jetbrains.annotations.Nullable;

/**
 * REQ-LOG-007: размещение/снятие рамок и картин.
 * Размещение ловим через {@link EntityJoinLevelEvent} (исключая загрузку из чанка),
 * снятие — через {@link AttackEntityEvent} (атрибуция игрока известна). Вставка/поворот
 * предметов в рамке логируются в {@link com.gle.mixin.ItemFrameMixin}.
 */
public final class DecorationListener {

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!GLEConfig.enableItemFrames.get()) return;
        if (event.loadedFromDisk()) return; // не логируем существующие при загрузке мира
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (!isDecoration(e)) return;

        ItemStack held = (e instanceof ItemFrame f) ? f.getItem() : ItemStack.EMPTY;
        log(level, e, "PLACE", SystemUsers.uuidOf(SystemUsers.SERVER), held);
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!GLEConfig.enableItemFrames.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!isDecoration(target)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        ItemStack held = (target instanceof ItemFrame f) ? f.getItem() : ItemStack.EMPTY;
        log(level, target, "BREAK", player.getUUID().toString(), held);
    }

    private static boolean isDecoration(Entity e) {
        return e instanceof ItemFrame || e instanceof Painting;
    }

    private static void log(ServerLevel level, Entity e, String action, String userUuid, ItemStack item) {
        if (!GLStorage.isReady()) return;
        BlockPos pos = e.blockPosition();
        String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));

        String itemName = null;
        byte[] itemNbt = null;
        if (item != null && !item.isEmpty()) {
            itemName = GLMaterials.normalize(BuiltInRegistries.ITEM.getKey(item.getItem()));
            try { itemNbt = ItemData.serialize(item, level.registryAccess()); } catch (Exception ignored) {}
        }

        GLStorage.get().events().insertWorldEntity(new GleEventsDao.WorldEntityEntry(
                System.currentTimeMillis(), userUuid,
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                type, e.getUUID().toString(), action,
                itemName, itemNbt, "player", null
        ));
    }
}
