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
import net.minecraft.world.level.block.entity.BlockEntity;
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
        if (!(level.getBlockEntity(pos) instanceof Container container)) return false;

        ItemStack stack = reconstruct(level, change);
        if (stack.isEmpty()) return false;

        boolean wasAdd = change.action() == GLActions.ADD_ITEM || change.action() == GLActions.PICKUP_ITEM;
        // откат: добавление → убрать, изъятие → вернуть. restore: наоборот.
        boolean removeNow = reverse == wasAdd;
        return removeNow ? removeMatching(container, stack, change.amount()) : insert(container, stack);
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

    private static boolean removeMatching(Container container, ItemStack stack, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack inSlot = container.getItem(slot);
            if (inSlot.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(inSlot, stack)) {
                int take = Math.min(remaining, inSlot.getCount());
                inSlot.shrink(take);
                container.setItem(slot, inSlot.isEmpty() ? ItemStack.EMPTY : inSlot);
                remaining -= take;
            }
        }
        container.setChanged();
        return remaining < amount; // хоть что-то удалили
    }

    private static boolean insert(Container container, ItemStack stack) {
        ItemStack rest = stack.copy();
        // Сначала докладываем в существующие стаки
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
        // Затем в пустые слоты
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
