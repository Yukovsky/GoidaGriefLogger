package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.core.ItemData;
import com.gle.core.db.ContainerLogDao;
import com.gle.core.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EnderChestBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Транзакции эндер-сундука (что игрок положил/взял из личного эндер-инвентаря) — таблица
 * {@code containers}, action ADD_ENDER=9 / REMOVE_ENDER=10.
 * <p>
 * Сам GriefLogger эти коды объявлял в enum, но НЕ логировал — это улучшение GoidaGriefLogger.
 * Эндер-инвентарь персональный и в блоке не хранится (нет block-capability), поэтому снимаем его
 * напрямую через {@code player.getEnderChestInventory()}: снимок при открытии меню, разница при
 * закрытии. Позиция — блок эндер-сундука, у которого открыли.
 */
public final class EnderChestListener {

    private record Pending(String dim, BlockPos pos, long time) {}
    private record OpenSnapshot(String dim, BlockPos pos, Map<String, ItemStack> before) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, OpenSnapshot> OPEN = new ConcurrentHashMap<>();

    /** Игрок должен быть рядом с сундуком, снимок которого мы берём. */
    private static final double MAX_REACH_SQR = 8.0 * 8.0;

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        // Обе руки: эндер-сундук открывается и вторичной рукой (см. ContainerTransactionListener).
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer) || player instanceof FakePlayer) return;
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof EnderChestBlock)) return;

        PENDING.put(player.getUUID(),
                new Pending(level.dimension().location().toString(), event.getPos().immutable(), System.currentTimeMillis()));
    }

    @SubscribeEvent
    public void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        Pending pend = PENDING.remove(sp.getUUID());
        if (pend == null) return;
        // Вместо таймаута «2 секунды» (терял транзакцию при любом лаге сервера) — проверка
        // по существу: тот же мир и игрок рядом с сундуком.
        if (!pend.dim().equals(sp.level().dimension().location().toString())) return;
        if (sp.blockPosition().distSqr(pend.pos()) > MAX_REACH_SQR) return;
        Container ender = sp.getEnderChestInventory();
        OPEN.put(sp.getUUID(), new OpenSnapshot(pend.dim(), pend.pos(), snapshot(ender, sp.level().registryAccess())));
    }

    @SubscribeEvent
    public void onClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        finish(sp);
    }

    /**
     * Выход игрока с открытым эндер-сундуком: {@link PlayerContainerEvent.Close} может не прийти,
     * и раньше снимок оставался в карте навсегда — утечка плюс потерянная транзакция.
     */
    @SubscribeEvent
    public void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer sp) finish(sp);
    }

    private static void finish(ServerPlayer sp) {
        OpenSnapshot snap = OPEN.remove(sp.getUUID());
        if (snap == null || !GLStorage.isReady()) return;
        Map<String, ItemStack> after = snapshot(sp.getEnderChestInventory(), sp.level().registryAccess());
        logDiff(snap, sp, after);
    }

    private static Map<String, ItemStack> snapshot(Container container, RegistryAccess reg) {
        Map<String, ItemStack> map = new HashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty()) continue;
            String key = key(s, reg);
            ItemStack agg = map.get(key);
            if (agg == null) map.put(key, s.copy());
            else agg.grow(s.getCount());
        }
        return map;
    }

    private static void logDiff(OpenSnapshot snap, ServerPlayer player, Map<String, ItemStack> after) {
        RegistryAccess reg = player.level().registryAccess();
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
            int action = delta > 0 ? GLActions.ADD_ENDER : GLActions.REMOVE_ENDER;
            int amount = Math.abs(delta);

            ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(rep.getItem());
            String material = GLMaterials.normalize(itemKey);
            byte[] data;
            try {
                data = ItemData.serialize(rep, reg);
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
