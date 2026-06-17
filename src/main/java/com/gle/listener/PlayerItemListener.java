package com.gle.listener;

import com.gle.core.GLActions;
import com.gle.core.ItemLogger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

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
        ItemLogger.logPlayerItem(player, stack, action);
    }
}
