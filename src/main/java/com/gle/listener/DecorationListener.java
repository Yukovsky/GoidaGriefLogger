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
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REQ-LOG-007: размещение/снятие рамок и картин.
 * Размещение ловим через {@link EntityJoinLevelEvent} (исключая загрузку из чанка),
 * снятие — через {@link AttackEntityEvent} (атрибуция игрока известна). Вставка/поворот
 * предметов в рамке логируются в {@link com.gle.mixin.ItemFrameMixin}.
 */
public final class DecorationListener {

    /** Клик предметом-рамкой/картиной: игрок и место, чтобы связать с появлением сущности. */
    private record Placing(BlockPos pos, long time) {}

    private static final Map<UUID, Placing> PLACING = new ConcurrentHashMap<>();
    /** Сущность появляется в тот же тик, что и клик; запас на лаг. */
    private static final long PLACE_WINDOW_MS = 2000;
    private static final double PLACE_RANGE_SQR = 6.0 * 6.0;

    /** Запоминаем попытку повесить рамку/картину, чтобы установка не писалась на [SERVER]. */
    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!GLEConfig.enableItemFrames.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(event.getItemStack().getItem() instanceof HangingEntityItem)) return;
        PLACING.put(sp.getUUID(), new Placing(event.getPos().immutable(), System.currentTimeMillis()));
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!GLEConfig.enableItemFrames.get()) return;
        if (event.loadedFromDisk()) return; // не логируем существующие при загрузке мира
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (!isDecoration(e)) return;

        ItemStack held = (e instanceof ItemFrame f) ? f.getItem() : ItemStack.EMPTY;
        // Раньше установка ЖЁСТКО писалась на [SERVER], хотя снятие атрибутировалось игроку:
        // /gl lookup по любой рамке всегда утверждал, что её повесил сервер.
        String placer = resolvePlacer(e);
        log(level, e, "PLACE",
                placer != null ? placer : SystemUsers.uuidOf(SystemUsers.SERVER), held);
    }

    /** uuid игрока, только что кликнувшего рамкой/картиной рядом с этой позицией, либо null. */
    @Nullable
    private static String resolvePlacer(Entity e) {
        long now = System.currentTimeMillis();
        BlockPos at = e.blockPosition();
        for (Map.Entry<UUID, Placing> entry : PLACING.entrySet()) {
            Placing p = entry.getValue();
            if (now - p.time() > PLACE_WINDOW_MS) { PLACING.remove(entry.getKey()); continue; }
            if (at.distSqr(p.pos()) <= PLACE_RANGE_SQR) {
                PLACING.remove(entry.getKey());
                return entry.getKey().toString();
            }
        }
        return null;
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
