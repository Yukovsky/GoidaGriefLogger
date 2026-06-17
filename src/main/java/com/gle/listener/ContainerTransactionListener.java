package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.core.ItemData;
import com.gle.db.ContainerLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Логирование того, что игрок ВЗЯЛ/ПОЛОЖИЛ в хранилища — и ванильные (сундуки/бочки/печи/…),
 * и модовые на capability (поставленный Sophisticated Backpacks, ящики-моды, Create-вместилища) —
 * таблица {@code containers}, action REMOVE_ITEM=0 / ADD_ITEM=1.
 * <p>
 * После поглощения GriefLogger (Путь E) единый писатель ведёт транзакции для ВСЕХ контейнеров.
 * Содержимое снимается единообразно через {@code Capabilities.ItemHandler.BLOCK} (NeoForge
 * регистрирует item-handler и для ванильных контейнеров):
 * <ol>
 *   <li>right-click по блоку с хендлером → запоминаем позицию;</li>
 *   <li>{@link PlayerContainerEvent.Open} → снимаем снимок предметов хендлера;</li>
 *   <li>{@link PlayerContainerEvent.Close} → читаем снова, считаем разницу, пишем дельты.</li>
 * </ol>
 */
public final class ContainerTransactionListener {

    private record Pending(String dim, BlockPos pos, long time) {}
    private record OpenSnapshot(String dim, BlockPos pos, Map<String, ItemStack> before) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, OpenSnapshot> OPEN = new ConcurrentHashMap<>();

    /** Клик по блоку с предметным хендлером (не ванильный контейнер) — запоминаем позицию. */
    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer) || player instanceof FakePlayer) return;

        BlockPos pos = event.getPos();
        if (!isTrackable(level, pos)) return;
        PENDING.put(player.getUUID(),
                new Pending(level.dimension().location().toString(), pos.immutable(), System.currentTimeMillis()));
    }

    /** Меню открылось — снимаем снимок содержимого хендлера у запомненной позиции. */
    @SubscribeEvent
    public void onOpen(PlayerContainerEvent.Open event) {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        Pending pend = PENDING.remove(sp.getUUID());
        if (pend == null || System.currentTimeMillis() - pend.time() > 2000) return; // не от нашего клика
        if (!(sp.level() instanceof ServerLevel level)) return;

        IItemHandler handler = handlerAt(level, pend.pos());
        if (handler == null) return;
        OPEN.put(sp.getUUID(), new OpenSnapshot(pend.dim(), pend.pos(), snapshot(handler, level.registryAccess())));
    }

    /** Меню закрылось — сравниваем «до» и «после», пишем дельты. */
    @SubscribeEvent
    public void onClose(PlayerContainerEvent.Close event) {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        OpenSnapshot snap = OPEN.remove(sp.getUUID());
        if (snap == null || !GLStorage.isReady()) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        IItemHandler handler = handlerAt(level, snap.pos());
        if (handler == null) return;
        Map<String, ItemStack> after = snapshot(handler, level.registryAccess());
        logDiff(level, snap, sp, after);
    }

    /**
     * Подходит ли блок: есть предметный хендлер. После поглощения GriefLogger (Путь E) сюда
     * попадают И ванильные контейнеры ({@code BaseContainerBlockEntity} — сундуки/бочки/печи/…),
     * И модовые хранилища на capability — единый писатель ведёт транзакции для всех.
     * Эндер-сундук исключён: его персональный инвентарь в блоке не хранится, им занимается
     * {@link EnderChestListener}.
     */
    private static boolean isTrackable(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be instanceof EnderChestBlockEntity) return false;
        return handlerAt(level, pos) != null;
    }

    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        try {
            return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Снимок: ключ (предмет+компоненты) -> агрегированный стек с суммарным количеством. */
    private static Map<String, ItemStack> snapshot(IItemHandler handler, RegistryAccess reg) {
        Map<String, ItemStack> map = new HashMap<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty()) continue;
            String key = key(s, reg);
            ItemStack agg = map.get(key);
            if (agg == null) map.put(key, s.copy());
            else agg.grow(s.getCount());
        }
        return map;
    }

    private static void logDiff(ServerLevel level, OpenSnapshot snap, ServerPlayer player, Map<String, ItemStack> after) {
        Map<String, ItemStack> before = snap.before();
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());

        for (String key : keys) {
            ItemStack b = before.get(key);
            ItemStack a = after.get(key);
            int bc = b == null ? 0 : b.getCount();
            int ac = a == null ? 0 : a.getCount();
            int delta = ac - bc;
            if (delta == 0) continue;

            ItemStack rep = a != null ? a : b;
            int action = delta > 0 ? GLActions.ADD_ITEM : GLActions.REMOVE_ITEM;
            int amount = Math.abs(delta);

            ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(rep.getItem());
            String material = GLMaterials.normalize(itemKey);
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
                    material,
                    data,
                    amount,
                    action));
        }
    }

    /** Канонический ключ предмета: registry-имя + base64 байтов компонентов. */
    private static String key(ItemStack s, RegistryAccess reg) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        String comp;
        try {
            comp = Base64.getEncoder().encodeToString(ItemData.serialize(s, reg));
        } catch (Exception e) {
            comp = "";
        }
        return id + "#" + comp;
    }
}
