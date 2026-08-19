package com.gle.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Учёт изменений инвентаря, которые машина делает САМА (печь жжёт топливо и плавит руду,
 * варочная стойка тратит ингредиент и топливо).
 * <p>
 * Зачем это нужно. Транзакции контейнера считаются как разница «снимок при открытии — снимок
 * при закрытии». Машина тикает и при открытом GUI, поэтому её собственная работа попадала
 * в эту разницу и записывалась на игрока. Прежнее обходное решение — не писать убыль во входе
 * и топливе — закрывало ложные записи, но открывало дыру: предмет можно было положить
 * в неподходящий слот печи и позже незаметно забрать.
 * <p>
 * Теперь машина отчитывается о собственных изменениях сама (миксины в {@code serverTick}),
 * и они ВЫЧИТАЮТСЯ из общей разницы. Остаток — ровно то, что сделал игрок, по всем слотам
 * и в обе стороны.
 * <p>
 * Учёт включается только пока контейнер кем-то открыт: вне этого окна разница никого
 * не интересует, а тик машины не должен ничего стоить.
 */
public final class MachineActivity {

    private MachineActivity() {}

    /** posLong -> (ключ предмета -> суммарное изменение количества, сделанное машиной). */
    private static final Map<Long, Map<String, Integer>> TRACKED = new ConcurrentHashMap<>();

    /** Начать учёт для позиции (вызывается при открытии контейнера). */
    public static void start(BlockPos pos) {
        TRACKED.put(pos.asLong(), new ConcurrentHashMap<>());
    }

    /** Завершить учёт и забрать накопленное. Пустая карта, если учёт не вёлся. */
    public static Map<String, Integer> stop(BlockPos pos) {
        Map<String, Integer> m = TRACKED.remove(pos.asLong());
        return m == null ? Map.of() : m;
    }

    /** Ведётся ли учёт для позиции. Горячий путь: вызывается из тика машины. */
    public static boolean isTracked(BlockPos pos) {
        return !TRACKED.isEmpty() && TRACKED.containsKey(pos.asLong());
    }

    /**
     * Снимок содержимого контейнера в виде «ключ предмета -> количество».
     * Ключ должен совпадать с тем, что использует слушатель транзакций.
     */
    public static Map<String, Integer> snapshot(Container c, net.minecraft.core.RegistryAccess reg) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            map.merge(ItemKey.of(s, reg), s.getCount(), Integer::sum);
        }
        return map;
    }

    /** Записать разницу, которую машина произвела за свой тик. */
    public static void record(BlockPos pos, Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> acc = TRACKED.get(pos.asLong());
        if (acc == null) return;
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int delta = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (delta != 0) acc.merge(e.getKey(), delta, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            if (after.containsKey(e.getKey())) continue;
            acc.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
    }
}
