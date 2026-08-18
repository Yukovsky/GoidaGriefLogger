package com.gle.core.rollback;

import com.gle.core.GLActions;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Реверс изменений содержимого контейнеров.
 * <ul>
 *   <li>ADD_ITEM (предмет был добавлен) → удалить {@code amount} предмета из контейнера;</li>
 *   <li>REMOVE_ITEM (предмет был извлечён) → вернуть предмет в контейнер.</li>
 * </ul>
 * Best-effort: если контейнер отсутствует/полон, операция помечается неуспешной.
 */
public final class ItemRestorer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/ItemRestore");

    private ItemRestorer() {}

    /**
     * @param reverse true = откат (обратить запись), false = restore (повторить запись).
     */
    public static boolean apply(ServerLevel level, RollbackData.ItemChange change, boolean reverse) {
        BlockPos pos = new BlockPos(change.x(), change.y(), change.z());
        // Захват содержимого идёт через capability ItemHandler, поэтому и восстановление обязано.
        // Раньше здесь стоял только `instanceof Container`, а модовые вместилища (Create Item Vault,
        // Sophisticated Backpacks, Tom's Storage) Container НЕ реализуют — для них откат молча
        // возвращал false, то есть «откат контейнеров» для них просто не работал.
        IItemHandler handler = handlerAt(level, pos);
        if (handler == null) return false;

        ItemStack stack = reconstruct(level, change);
        if (stack.isEmpty()) return false;

        // containers содержит только ADD_ITEM/REMOVE_ITEM (PICKUP пишется в таблицу items и сюда не выбирается).
        boolean wasAdd = change.action() == GLActions.ADD_ITEM;
        // откат: добавление → убрать, изъятие → вернуть. restore: наоборот.
        boolean removeNow = reverse == wasAdd;
        return removeNow
                ? removeMatching(handler, stack, change.amount())
                : insert(level, pos, handler, stack);
    }

    /**
     * Предметный хендлер на позиции: сначала capability (покрывает и ванильные контейнеры —
     * NeoForge регистрирует ItemHandler и для них), затем — {@link Container} через обёртку.
     */
    @org.jetbrains.annotations.Nullable
    private static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        try {
            IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (cap != null) return cap;
        } catch (Exception ignored) {
            // капability может бросить у кривых модовых блоков — падаем на Container ниже
        }
        return level.getBlockEntity(pos) instanceof Container c ? new InvWrapper(c) : null;
    }

    private static ItemStack reconstruct(ServerLevel level, RollbackData.ItemChange change) {
        ResourceLocation id = change.material().contains(":")
                ? ResourceLocation.parse(change.material())
                : ResourceLocation.fromNamespaceAndPath("minecraft", change.material());
        var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item, Math.max(1, change.amount()));
        if (change.data() != null && change.data().length > 0) {
            try {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                        Unpooled.wrappedBuffer(change.data()), level.registryAccess());
                DataComponentPatch patch = DataComponentPatch.STREAM_CODEC.decode(buf);
                stack.applyComponents(patch);
            } catch (Exception e) {
                LOGGER.debug("Не удалось декодировать компоненты предмета: {}", e.getMessage());
            }
        }
        return stack;
    }

    private static boolean removeMatching(IItemHandler handler, ItemStack stack, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(inSlot, stack)) continue;
            ItemStack taken = handler.extractItem(slot, remaining, false);
            remaining -= taken.getCount();
        }
        return remaining < amount; // хоть что-то удалили
    }

    private static boolean insert(ServerLevel level, BlockPos pos, IItemHandler handler, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) {
            rest = handler.insertItem(slot, rest, false);
        }
        if (rest.isEmpty()) return true;
        // Хендлер может отказать по правилам слота (классика — слот результата печи: положить туда
        // ничего нельзя). Для откатов это неверно: мы возвращаем то, что там РЕАЛЬНО лежало.
        // Поэтому остаток дожимаем напрямую через Container, как делала прежняя реализация.
        return forceIntoContainer(level, pos, rest);
    }

    private static boolean forceIntoContainer(ServerLevel level, BlockPos pos, ItemStack rest) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) return false;
        for (int slot = 0; slot < container.getContainerSize() && !rest.isEmpty(); slot++) {
            ItemStack inSlot = container.getItem(slot);
            if (!inSlot.isEmpty() && ItemStack.isSameItemSameComponents(inSlot, rest)) {
                int max = Math.min(container.getMaxStackSize(), inSlot.getMaxStackSize());
                int add = Math.min(rest.getCount(), max - inSlot.getCount());
                if (add > 0) {
                    inSlot.grow(add);
                    container.setItem(slot, inSlot);
                    rest.shrink(add);
                }
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && !rest.isEmpty(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                int max = Math.min(container.getMaxStackSize(), rest.getMaxStackSize());
                int put = Math.min(rest.getCount(), max);
                container.setItem(slot, rest.copyWithCount(put));
                rest.shrink(put);
            }
        }
        container.setChanged();
        return rest.isEmpty();
    }
}
