package com.gle.core;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Base64;

/**
 * Канонический ключ предмета: registry-имя плюс base64 байтов компонентов.
 * <p>
 * Вынесен из слушателя транзакций, потому что тот же ключ считает {@link MachineActivity}:
 * вычитание собственной работы машины из разницы контейнера имеет смысл, только если обе
 * стороны считают ключ ОДИНАКОВО.
 */
public final class ItemKey {

    private ItemKey() {}

    public static String of(ItemStack s, RegistryAccess reg) {
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
