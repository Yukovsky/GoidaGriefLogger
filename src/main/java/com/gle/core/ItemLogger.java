package com.gle.core;

import com.gle.GLEConfig;
import com.gle.db.ContainerLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Высокоуровневый помощник записи перемещений предметов (контейнеры/автоматизация).
 */
public final class ItemLogger {

    private ItemLogger() {}

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
