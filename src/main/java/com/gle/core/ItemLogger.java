package com.gle.core;

import com.gle.GLEConfig;
import com.gle.db.ContainerLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Высокоуровневый помощник записи перемещений предметов (контейнеры/автоматизация) и действий
 * игрока с предметами «в руках/на земле».
 */
public final class ItemLogger {

    private ItemLogger() {}

    /**
     * Залогировать действие игрока с предметом в таблицу {@code items} от имени UUID игрока.
     * Общий путь для {@link com.gle.listener.PlayerItemListener} (выброс/крафт/съедание) и
     * миксинов throw/shoot/break. Позиция — блок-позиция игрока (как делал GriefLogger).
     * <p>
     * Вызывающий обязан отфильтровать fake-players (чтобы ядро не зависело от классов NeoForge).
     *
     * @param action один из {@link GLActions} DROP/PICKUP/CRAFT/BREAK/CONSUME/THROW/SHOOT
     */
    public static void logPlayerItem(ServerPlayer player, ItemStack stack, int action) {
        if (!GLStorage.isReady()) return;
        if (stack == null || stack.isEmpty()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        String dimension = level.dimension().location().toString();
        if (contains(GLEConfig.worldBlacklist.get(), dimension)) return;

        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String material = GLMaterials.normalize(itemKey);
        if (contains(GLEConfig.blockBlacklist.get(), material)) return;
        if (itemKey != null && contains(GLEConfig.modBlacklist.get(), itemKey.getNamespace())) return;

        byte[] data;
        try {
            data = ItemData.serialize(stack, level.registryAccess());
        } catch (Exception e) {
            data = null;
        }

        BlockPos pos = player.blockPosition();
        GLStorage.get().containers().insertItem(new ContainerLogDao.ContainerEntry(
                System.currentTimeMillis(),
                player.getUUID().toString(),
                dimension,
                pos.getX(), pos.getY(), pos.getZ(),
                material,
                data,
                stack.getCount(),
                action));
    }

    /**
     * Залогировать перемещение предмета в позицию контейнера.
     *
     * @param action {@link GLActions#REMOVE_ITEM} или {@link GLActions#ADD_ITEM}
     */
    public static void log(ServerLevel level, BlockPos pos, ItemStack stack, int amount,
                           int action, String sourceType, String systemUser) {
        if (!GLStorage.isReady()) return;
        if (stack.isEmpty() || amount <= 0) return;

        String dimension = level.dimension().location().toString();
        if (contains(GLEConfig.worldBlacklist.get(), dimension)) return;
        if (sourceType != null && contains(GLEConfig.sourceTypeBlacklist.get(), sourceType)) return;

        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String material = GLMaterials.normalize(itemKey);

        byte[] data;
        try {
            data = ItemData.serialize(stack, level.registryAccess());
        } catch (Exception e) {
            data = null;
        }

        ContainerLogDao.ContainerEntry entry = new ContainerLogDao.ContainerEntry(
                System.currentTimeMillis(),
                SystemUsers.uuidOf(systemUser),
                dimension,
                pos.getX(), pos.getY(), pos.getZ(),
                material,
                data,
                amount,
                action
        );
        GLStorage.get().containers().insert(entry);
    }

    private static boolean contains(List<? extends String> list, String value) {
        return list != null && list.contains(value);
    }
}
