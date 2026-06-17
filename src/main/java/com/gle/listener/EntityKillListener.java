package com.gle.listener;

import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Убийство сущности игроком — игровое событие, которое раньше писал GriefLogger
 * (BlockAction KILL=3, имя сущности в справочнике {@code entities}). После поглощения GL (Путь E)
 * пишет единый writer GoidaGriefLogger.
 */
public final class EntityKillListener {

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!GLStorage.isReady()) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer) || killer instanceof FakePlayer) return;

        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;

        ResourceLocation entityKey = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType());
        if (entityKey == null) return;
        // ВНИМАНИЕ: для сущностей GriefLogger хранит ПОЛНОЕ имя с префиксом (minecraft:zombie),
        // в отличие от материалов (без префикса). Сохраняем как у GL для совместимости.
        String entityName = entityKey.toString();

        String dimension = level.dimension().location().toString();
        if (contains(dimension)) return;

        BlockPos pos = victim.blockPosition();
        GLStorage.get().blocks().insertEntityKill(
                System.currentTimeMillis(),
                killer.getUUID().toString(),
                dimension,
                pos.getX(), pos.getY(), pos.getZ(),
                entityName);
    }

    private static boolean contains(String dimension) {
        var list = com.gle.GLEConfig.worldBlacklist.get();
        return list != null && list.contains(dimension);
    }
}
