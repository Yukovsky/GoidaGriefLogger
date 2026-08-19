package com.gle.integration.curios;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Доступ к надетым аксессуарам Curios, если этот мод установлен.
 * <p>
 * Curios держит свои слоты ОТДЕЛЬНО от {@code player.getInventory()}, поэтому снимок инвентаря
 * при смерти их не видел: кольца, пояса и прочее не попадали в лог и восстановить их после
 * отката дропа было не из чего.
 * <p>
 * Обращение рефлексией — по образцу {@link com.gle.permission.FtbRanksPermissions}: мод не должен
 * тянуть Curios в зависимости, а при его отсутствии просто ничего не возвращает.
 */
public final class CuriosSupport {

    /** true = Curios точно отсутствует (классы не загрузились ни разу) — повторных попыток нет. */
    private static volatile boolean absent = false;
    private static volatile Method getCuriosInventory; // CuriosApi#getCuriosInventory(LivingEntity)
    private static volatile Method getEquippedCurios;  // ICuriosItemHandler#getEquippedCurios()

    private CuriosSupport() {}

    private static boolean init() {
        if (getCuriosInventory != null) return true;
        if (absent) return false;
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Method equipped = handler.getMethod("getEquippedCurios");
            Method inv = api.getMethod("getCuriosInventory", LivingEntity.class);
            getEquippedCurios = equipped;
            getCuriosInventory = inv; // ставим последним: признак готовности
            return true;
        } catch (Throwable t) {
            absent = true;
            return false;
        }
    }

    /** Установлен ли Curios (после первой удачной инициализации). */
    public static boolean available() {
        return init();
    }

    /**
     * Надетые аксессуары игрока. Пустой список, если Curios не установлен, слотов нет
     * или что-то пошло не так — вызывающий в любом случае продолжает работать.
     */
    public static List<ItemStack> equipped(LivingEntity entity) {
        if (entity == null || !init()) return List.of();
        try {
            Object opt = getCuriosInventory.invoke(null, entity);
            if (!(opt instanceof Optional<?> optional) || optional.isEmpty()) return List.of();
            Object handler = getEquippedCurios.invoke(optional.get());
            if (!(handler instanceof IItemHandlerModifiable items)) return List.of();

            List<ItemStack> out = new ArrayList<>();
            for (int slot = 0; slot < items.getSlots(); slot++) {
                ItemStack stack = items.getStackInSlot(slot);
                if (!stack.isEmpty()) out.add(stack.copy());
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }
}
