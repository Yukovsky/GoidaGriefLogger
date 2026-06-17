package com.gle.core;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * Сериализация компонентов предмета в тот же байтовый формат, что использует GriefLogger
 * для колонки {@code containers.data} (см. {@code SimpleItemStack.getTagBytes}):
 * {@code DataComponentPatch.STREAM_CODEC.encode(RegistryFriendlyByteBuf, patch)}.
 * Это гарантирует, что записи GLE читаются инспектором GL без расхождений.
 */
public final class ItemData {

    private ItemData() {}

    public static byte[] serialize(ItemStack stack, RegistryAccess registryAccess) {
        DataComponentPatch patch = stack.getComponentsPatch();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
        DataComponentPatch.STREAM_CODEC.encode(buf, patch);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
}
