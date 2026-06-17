package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.core.ItemData;
import com.gle.db.ContainerLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

import java.util.List;

/**
 * Логирование подбора предметов игроком с земли (ItemAction PICKUP=3 → таблица {@code items}).
 * <p>
 * GriefLogger 1.21.1 декларирует подбор через architectury {@code PlayerEvent.PICKUP_ITEM_POST},
 * но этот мост на NeoForge 1.21 не срабатывает (ванильное событие подбора заменено на
 * {@link ItemEntityPickupEvent}), поэтому фактически подбор не пишется. GLE логирует его сам
 * через {@code ItemEntityPickupEvent.Post}, в тот же формат, что и GL (читается его инспектором).
 */
public final class ItemPickupListener {

    @SubscribeEvent
    public void onPickup(ItemEntityPickupEvent.Post event) {
        if (!GLEConfig.enableItemPickup.get()) return;
        if (!GLStorage.isReady()) return;

        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        ItemStack stack = event.getOriginalStack(); // стек, лежавший на земле до подбора
        if (stack == null || stack.isEmpty()) return;

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

        BlockPos pos = sp.blockPosition();
        ContainerLogDao.ContainerEntry entry = new ContainerLogDao.ContainerEntry(
                System.currentTimeMillis(),
                sp.getUUID().toString(),
                dimension,
                pos.getX(), pos.getY(), pos.getZ(),
                material,
                data,
                stack.getCount(),
                GLActions.PICKUP_ITEM
        );
        GLStorage.get().containers().insertItem(entry);
    }

    private static boolean contains(List<? extends String> list, String value) {
        return list != null && list.contains(value);
    }
}
