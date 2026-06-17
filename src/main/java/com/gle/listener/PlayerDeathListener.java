package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.NbtUtil;
import com.gle.core.SourceType;
import com.gle.db.GLStorage;
import com.gle.db.GleEventsDao;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * REQ-LOG-008: снимок инвентаря игрока в момент смерти (для роллбека дропа {@code -inventory})
 * и логирование появления могилы, если установлен мод надгробий.
 * <p>
 * Снимок берём на {@link EventPriority#HIGHEST} — ДО того как моды успеют забрать инвентарь:
 * keepInventory или мод-надгробие (henkelmax/gravestone) кладёт предметы в блок и вызывает
 * {@code removeDrops()}, поэтому на LOWEST инвентарь игрока уже был бы пуст.
 * <p>
 * Интеграция с надгробиями (soft-dep, без зависимости): мод {@code gravestone} ставит блок
 * могилы прямым {@code setBlockAndUpdate} во время обработки смерти. В конце тика сканируем
 * окрестности места смерти и, найдя блок из пространства имён {@code gravestone}, пишем его
 * установку от имени игрока (source {@code grave}). Если мода нет — блок не появится и записи
 * не будет, а предметы просто выпадут (их логирует сам GriefLogger).
 */
public final class PlayerDeathListener {

    private static final String GRAVESTONE_MODID = "gravestone";
    private static final int GRAVE_SCAN_RADIUS = 4;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeath(LivingDeathEvent event) {
        if (!GLEConfig.enablePlayerDeath.get()) return;
        if (!GLStorage.isReady()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        byte[] invNbt = null;
        try {
            ListTag list = player.getInventory().save(new ListTag());
            CompoundTag root = new CompoundTag();
            root.put("inv", list);
            invNbt = NbtUtil.compress(root);
        } catch (Exception ignored) {
            // graceful degradation — пишем запись без инвентаря
        }

        String cause;
        try {
            cause = event.getSource().type().msgId();
        } catch (Exception e) {
            cause = "unknown";
        }

        BlockPos pos = player.blockPosition();
        ServerLevel level = (ServerLevel) player.level();
        GLStorage.get().events().insertPlayerDeath(new GleEventsDao.PlayerDeathEntry(
                System.currentTimeMillis(),
                player.getUUID().toString(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                cause,
                invNbt
        ));

        // Могила появляется во время обработки смерти (тот же тик). Ищем её в конце тика.
        if (ModList.get().isLoaded(GRAVESTONE_MODID)) {
            String userUuid = player.getUUID().toString();
            player.server.execute(() -> scanForGrave(level, pos, userUuid));
        }
    }

    /** Найти поставленный грейв-блок рядом с местом смерти и залогировать его установку. */
    private static void scanForGrave(ServerLevel level, BlockPos center, String userUuid) {
        if (!GLStorage.isReady()) return;
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dy = -GRAVE_SCAN_RADIUS; dy <= GRAVE_SCAN_RADIUS; dy++) {
            for (int dx = -GRAVE_SCAN_RADIUS; dx <= GRAVE_SCAN_RADIUS; dx++) {
                for (int dz = -GRAVE_SCAN_RADIUS; dz <= GRAVE_SCAN_RADIUS; dz++) {
                    cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cur);
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if (key != null && GRAVESTONE_MODID.equals(key.getNamespace())) {
                        BlockLogger.logAs(level, cur.immutable(), state, GLActions.PLACE_BLOCK,
                                SourceType.GRAVE, userUuid, null, null, false);
                        return; // одна могила на смерть
                    }
                }
            }
        }
    }
}
