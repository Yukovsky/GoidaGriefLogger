package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.core.ContainerAccess;
import com.gle.core.ItemData;
import com.gle.core.MachineActivity;
import com.gle.core.db.ContainerLogDao;
import com.gle.core.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.Base64;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /** Игрок должен быть рядом с блоком, снимок которого мы берём (замена таймауту «2 секунды»). */
    private static final double MAX_REACH_SQR = 8.0 * 8.0;

    /** Клик по блоку с предметным хендлером (не ванильный контейнер) — запоминаем позицию. */
    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        // Обе руки: сундук открывается и вторичной рукой, а раньше такое открытие не логировалось.
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer) || player instanceof FakePlayer) return;

        BlockPos pos = event.getPos();
        if (!isTrackable(level, pos)) return;
        PENDING.put(player.getUUID(),
                new Pending(level.dimension().location().toString(), pos.immutable(), System.currentTimeMillis()));
    }

    /**
     * Есть ли у этого игрока свежий клик по блоку-контейнеру, то есть ведёт ли меню этот
     * слушатель. Нужно {@link CarriedContainerListener}, чтобы не логировать одно и то же дважды.
     */
    static boolean hasBlockContext(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID()) || OPEN.containsKey(player.getUUID());
    }

    /** Меню открылось — снимаем снимок содержимого хендлера у запомненной позиции. */
    @SubscribeEvent
    public void onOpen(PlayerContainerEvent.Open event) {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        Pending pend = PENDING.remove(sp.getUUID());
        if (pend == null) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        // Раньше здесь стоял таймаут в 2 секунды — при лаге сервера >2 с ВСЯ транзакция терялась
        // молча. Вместо времени проверяем то, что действительно важно: блок всё ещё на месте,
        // это тот же мир, и игрок физически рядом с ним.
        if (!pend.dim().equals(level.dimension().location().toString())) return;
        if (sp.blockPosition().distSqr(pend.pos()) > MAX_REACH_SQR) return;

        IItemHandler handler = ContainerAccess.handlerAt(level, pend.pos());
        if (handler == null) return;
        OPEN.put(sp.getUUID(), new OpenSnapshot(pend.dim(), pend.pos(),
                snapshot(handler, level.registryAccess())));
        // С этого момента машина на этой позиции отчитывается о собственном расходе,
        // чтобы её работа не приписалась игроку и не скрыла его действия.
        MachineActivity.start(pend.pos());
    }

    /** Меню закрылось — сравниваем «до» и «после», пишем дельты. */
    @SubscribeEvent
    public void onClose(PlayerContainerEvent.Close event) {
        if (!GLEConfig.enableContainerTransactions.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        OpenSnapshot snap = OPEN.remove(sp.getUUID());
        if (snap == null || !GLStorage.isReady()) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        finishSnapshot(level, sp, snap);
    }

    /**
     * Игрок вышел с открытым контейнером: {@link PlayerContainerEvent.Close} в этом пути может
     * не прийти, а раньше снимок просто оставался в карте навсегда (утечка + потерянная транзакция).
     */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
        OpenSnapshot snap = OPEN.remove(event.getEntity().getUUID());
        if (snap == null) return;
        if (!GLStorage.isReady()) { MachineActivity.stop(snap.pos()); return; }
        if (event.getEntity() instanceof ServerPlayer sp && sp.level() instanceof ServerLevel level) {
            finishSnapshot(level, sp, snap);
        }
    }

    /**
     * Досчитать и записать разницу. Если контейнера больше нет (его сломали при открытом GUI —
     * ровно гриферский сценарий), раньше здесь стоял молчаливый {@code return} и вся разница
     * терялась. Теперь считаем, что всё содержимое изъято.
     */
    private static void finishSnapshot(ServerLevel level, ServerPlayer sp, OpenSnapshot snap) {
        IItemHandler handler = ContainerAccess.handlerAt(level, snap.pos());
        Map<String, ItemStack> after = handler == null
                ? Map.of()
                : snapshot(handler, level.registryAccess());
        logDiff(level, snap, sp, after, MachineActivity.stop(snap.pos()));
    }

    /**
     * Подходит ли блок: есть предметный хендлер. Сюда попадают И ванильные контейнеры
     * (NeoForge регистрирует item-handler и для них), И модовые хранилища на capability.
     * Эндер-сундук исключён: его персональный инвентарь в блоке не хранится, им занимается
     * {@link EnderChestListener}.
     */
    private static boolean isTrackable(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be instanceof EnderChestBlockEntity) return false;
        return ContainerAccess.handlerAt(level, pos) != null;
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

    /**
     * @param machine что машина (печь, варочная стойка) израсходовала и произвела сама за время,
     *                пока GUI был открыт. Вычитается из общей разницы: остаток — действия игрока.
     */
    private static void logDiff(ServerLevel level, OpenSnapshot snap, ServerPlayer player,
                                Map<String, ItemStack> after, Map<String, Integer> machine) {
        Map<String, ItemStack> before = snap.before();
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());

        for (String key : keys) {
            ItemStack b = before.get(key);
            ItemStack a = after.get(key);
            int bc = b == null ? 0 : b.getCount();
            int ac = a == null ? 0 : a.getCount();
            // Собственная работа машины — не действие игрока, вычитаем её.
            int delta = (ac - bc) - machine.getOrDefault(key, 0);
            if (delta == 0) continue;

            ItemStack rep = a != null ? a : b;
            int action = delta > 0 ? GLActions.ADD_ITEM : GLActions.REMOVE_ITEM;
            int amount = Math.abs(delta);

            write(level, snap, player, rep, amount, action);
        }
    }

    /** Единая точка записи строки {@code containers}. */
    private static void write(ServerLevel level, OpenSnapshot snap, ServerPlayer player,
                              ItemStack rep, int amount, int action) {
        if (amount <= 0 || rep.isEmpty()) return;
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

    /** Ключ предмета — общий с {@link MachineActivity}, иначе вычитание не сойдётся. */
    private static String key(ItemStack s, RegistryAccess reg) {
        return com.gle.core.ItemKey.of(s, reg);
    }
}
