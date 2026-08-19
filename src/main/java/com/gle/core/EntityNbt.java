package com.gle.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Снимок сущности для отката её убийства и оценка того, СТОИТ ЛИ его вообще хранить.
 * <p>
 * Наивное сохранение полного NBT каждого убитого моба неприемлемо по объёму: у каждой сущности
 * свои позиция, UUID и скорость, поэтому дедупликация по содержимому не дала бы ни одного
 * попадания. Поэтому снимок сначала очищается от изменчивых полей, а затем сравнивается
 * с эталоном для этого типа сущности. Обычный зомби после очистки совпадает с эталоном —
 * и не хранится вообще, откату достаточно типа. Хранится только то, что делает сущность
 * особенной: имя, экипировка, изменённые атрибуты, эффекты, флаги.
 */
public final class EntityNbt {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/EntityNbt");

    private EntityNbt() {}

    /**
     * Поля, не несущие смысла для восстановления: положение в мире, идентичность и всё, что
     * меняется каждый тик. Плюс здоровье и таймеры урона — в момент смерти они нулевые,
     * и сохранять их означало бы воскрешать труп.
     */
    private static final Set<String> VOLATILE = Set.of(
            "Pos", "Motion", "Rotation", "FallDistance", "Fire", "Air", "OnGround",
            "PortalCooldown", "UUID", "Dimension", "HasVisualFire", "TicksFrozen",
            "Health", "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount",
            "Brain", "leash", "Leash", "SleepingX", "SleepingY", "SleepingZ",
            "Passengers", "RootVehicle", "fall_distance");

    /** Эталонный (очищенный) снимок для типа сущности — считается один раз на тип. */
    private static final Map<EntityType<?>, String> DEFAULTS = new ConcurrentHashMap<>();

    /**
     * Снимок сущности, готовый к хранению.
     *
     * @return {@code null}, если хранить нечего: сущность неотличима от обычной особи своего типа,
     *         снимок не удалось снять, либо он превысил лимит размера.
     */
    public static byte @Nullable [] capture(Entity entity, int maxNbtSizeKb) {
        if (maxNbtSizeKb <= 0) return null;
        try {
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            strip(tag);

            String def = defaultFor(entity);
            if (def != null && def.equals(tag.toString())) return null; // обычная особь — хранить нечего

            byte[] bytes = NbtUtil.compress(tag);
            if (bytes.length > maxNbtSizeKb * 1024) {
                LOGGER.debug("Снимок сущности {} превысил лимит ({} б) — пропущен",
                        entity.getType(), bytes.length);
                return null;
            }
            return bytes;
        } catch (Exception e) {
            LOGGER.debug("Не удалось снять снимок сущности {}: {}", entity.getType(), e.getMessage());
            return null;
        }
    }

    /** Очистить снимок от изменчивых полей — без этого дедупликация не даст ни одного попадания. */
    public static void strip(CompoundTag tag) {
        for (String key : VOLATILE) tag.remove(key);
    }

    /**
     * Очищенный снимок «обычной» особи этого типа. Считается один раз и запоминается:
     * создание пробной сущности — не то, что можно делать на каждое убийство.
     */
    @Nullable
    private static String defaultFor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        return DEFAULTS.computeIfAbsent(entity.getType(), type -> {
            Entity probe = null;
            try {
                probe = type.create(level);
                if (probe == null) return "";
                CompoundTag tag = new CompoundTag();
                probe.saveWithoutId(tag);
                strip(tag);
                return tag.toString();
            } catch (Exception e) {
                return "";
            } finally {
                if (probe != null) probe.discard();
            }
        });
    }
}
