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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;

/**
 * Действия игрока с предметами «в руках/на земле» — выброс, крафт, съедание — в таблицу {@code items}.
 * Раньше их писал GriefLogger (ItemAction DROP=2 / CRAFT=4 / CONSUME=6); после поглощения GL
 * (Путь E) пишет ЕДИНЫЙ писатель GoidaGriefLogger. Подбор покрыт отдельным {@link ItemPickupListener}.
 * <p>
 * Выброс/выстрел/поломка предмета через миксины (ProjectileMixin/ItemMixin у GL) пока не перенесены —
 * это отдельная задача миксин-порта.
 */
public final class PlayerItemListener {

    /** Выброс предмета (Q / перетаскивание из инвентаря на землю) → DROP=2. */
    @SubscribeEvent
    public void onToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        ItemStack stack = event.getEntity().getItem();
        log(sp, stack, GLActions.DROP_ITEM);
    }

    /** Крафт предмета → CRAFT=4. */
    @SubscribeEvent
    public void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        log(sp, event.getCrafting(), GLActions.CRAFT_ITEM);
    }

    /** Съедание/выпивание (еда, зелья, молоко) → CONSUME=6. */
    @SubscribeEvent
    public void onConsume(LivingEntityUseItemEvent.Finish event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof ServerPlayer sp)) return;
        log(sp, event.getItem(), GLActions.CONSUME_ITEM);
    }

    private static void log(ServerPlayer player, ItemStack stack, int action) {
        if (player instanceof FakePlayer) return;
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

    private static boolean contains(List<? extends String> list, String value) {
        return list != null && list.contains(value);
    }
}
