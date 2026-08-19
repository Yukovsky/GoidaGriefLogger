package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.core.ItemData;
import com.gle.core.ItemKey;
import com.gle.core.db.ContainerLogDao;
import com.gle.core.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Транзакции в НОСИМЫХ вместилищах — рюкзаках и подобном, что открывается из инвентаря,
 * а не кликом по блоку.
 * <p>
 * Весь остальной контейнерный слой построен вокруг координаты блока: {@link ContainerTransactionListener}
 * требует {@code BlockEntity} на позиции, а {@code AutomationItemLogger} — разрешения capability
 * по блоку. У надетого рюкзака позиции в мире нет вообще, поэтому кража из него и укрывание
 * краденого не логировались ничем.
 * <p>
 * Сделано обобщённо, а не под конкретный мод: берётся любое меню, у которого есть слоты
 * с посторонним контейнером, и снимается разница между открытием и закрытием. Так покрываются
 * любые моды рюкзаков и сумок без кода под каждый.
 * <p>
 * Позиция записи — место игрока в момент закрытия: у носимого вместилища другой позиции нет.
 */
public final class CarriedContainerListener {

    private record OpenSnapshot(String dim, BlockPos pos, Map<String, ItemStack> before) {}

    private static final Map<UUID, OpenSnapshot> OPEN = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onOpen(PlayerContainerEvent.Open event) {
        if (!GLEConfig.enableCarriedContainers.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;
        // Меню, привязанное к блоку, ведёт ContainerTransactionListener — не дублируем.
        if (ContainerTransactionListener.hasBlockContext(sp)) return;

        Map<String, ItemStack> before = snapshot(event.getContainer(), sp, level.registryAccess());
        if (before == null) return;
        OPEN.put(sp.getUUID(), new OpenSnapshot(level.dimension().location().toString(),
                sp.blockPosition(), before));
    }

    @SubscribeEvent
    public void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        OpenSnapshot snap = OPEN.remove(sp.getUUID());
        if (snap == null || !GLStorage.isReady()) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        Map<String, ItemStack> after = snapshot(event.getContainer(), sp, level.registryAccess());
        logDiff(level, snap, sp, after == null ? Map.of() : after);
    }

    /** Выход игрока с открытым рюкзаком: Close может не прийти — дозаписываем и чистим. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OPEN.remove(event.getEntity().getUUID());
    }

    /**
     * Снимок слотов меню, не принадлежащих самому игроку.
     *
     * @return {@code null}, если постороннего вместилища в меню нет — тогда логировать нечего.
     */
    private static Map<String, ItemStack> snapshot(AbstractContainerMenu menu, ServerPlayer player,
                                                   RegistryAccess reg) {
        if (menu == null) return null;
        Set<Container> seen = new HashSet<>();
        Map<String, ItemStack> map = new HashMap<>();
        boolean foreign = false;
        for (Slot slot : menu.slots) {
            Container container = slot.container;
            if (!isForeign(container, player)) continue;
            foreign = true;
            // Контейнер обходим целиком один раз: слоты меню могут его дублировать.
            if (!seen.add(container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty()) continue;
                String key = ItemKey.of(s, reg);
                ItemStack agg = map.get(key);
                if (agg == null) map.put(key, s.copy());
                else agg.grow(s.getCount());
            }
        }
        return foreign ? map : null;
    }

    /**
     * Считается ли содержимое слота «посторонним», то есть переносимым между игроком и вместилищем.
     * Собственный инвентарь игрока исключён, как и временные контейнеры верстака: их содержимое
     * существует только пока меню открыто, и разница по ним была бы шумом, а не событием.
     */
    private static boolean isForeign(Container container, ServerPlayer player) {
        if (container == null) return false;
        if (container == player.getInventory()) return false;
        if (container instanceof Inventory) return false;
        if (container instanceof CraftingContainer || container instanceof ResultContainer) return false;
        return true;
    }

    private static void logDiff(ServerLevel level, OpenSnapshot snap, ServerPlayer player,
                                Map<String, ItemStack> after) {
        Set<String> keys = new HashSet<>(snap.before().keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            ItemStack b = snap.before().get(key);
            ItemStack a = after.get(key);
            int delta = (a == null ? 0 : a.getCount()) - (b == null ? 0 : b.getCount());
            if (delta == 0) continue;

            ItemStack rep = a != null ? a : b;
            ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(rep.getItem());
            byte[] data;
            try {
                data = ItemData.serialize(rep, level.registryAccess());
            } catch (Exception e) {
                data = null;
            }
            GLStorage.get().containers().insert(new ContainerLogDao.ContainerEntry(
                    System.currentTimeMillis(),
                    player.getUUID().toString(),
                    snap.dim(),
                    snap.pos().getX(), snap.pos().getY(), snap.pos().getZ(),
                    GLMaterials.normalize(itemKey),
                    data,
                    Math.abs(delta),
                    delta > 0 ? GLActions.ADD_ITEM : GLActions.REMOVE_ITEM));
        }
    }
}
