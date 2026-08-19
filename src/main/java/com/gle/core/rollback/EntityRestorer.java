package com.gle.core.rollback;

import com.gle.core.NbtUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Восстановление убитых сущностей. Откат возвращает сущность на место убийства, restore —
 * убирает её снова, оставаясь обратной операцией.
 * <p>
 * Снимок хранится очищенным от позиции, UUID и скорости (см. {@code EntityNbt}), поэтому
 * перед загрузкой эти поля проставляются заново: положение — из записи лога, UUID — новый,
 * иначе движок отвергнет сущность как дубликат уже существующей.
 */
public final class EntityRestorer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/EntityRestore");

    /** В каком радиусе искать сущность, чтобы убрать её при restore. */
    private static final double MATCH_RADIUS = 2.0;

    private EntityRestorer() {}

    /** @return {@code null}, если применено, иначе краткая причина отказа. */
    @Nullable
    public static String apply(ServerLevel level, RollbackData.EntityChange change, boolean reverse) {
        ResourceLocation id = ResourceLocation.tryParse(change.entityName());
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) return "неизвестная сущность '" + change.entityName() + "'";
        return reverse ? spawn(level, type, change) : removeOne(level, type, change);
    }

    @Nullable
    private static String spawn(ServerLevel level, EntityType<?> type, RollbackData.EntityChange ch) {
        double x = ch.x() + 0.5, y = ch.y(), z = ch.z() + 0.5;
        try {
            Entity entity = type.create(level);
            if (entity == null) return "не удалось создать " + ch.entityName();

            if (ch.nbt() != null) {
                CompoundTag tag = NbtUtil.decompress(ch.nbt());
                // Возвращаем то, что снимок намеренно не хранит: без Pos загрузка ставит 0,0,0.
                tag.put("Pos", doubles(x, y, z));
                tag.put("Motion", doubles(0, 0, 0));
                tag.put("Rotation", floats(level.random.nextFloat() * 360.0F, 0.0F));
                entity.load(tag);
            }
            entity.moveTo(x, y, z, entity.getYRot(), entity.getXRot());
            // Новый UUID: старый мог остаться за другой (в т.ч. живой) сущностью.
            entity.setUUID(UUID.randomUUID());

            if (!level.addFreshEntity(entity)) return "мир отверг сущность на " + x + ", " + y + ", " + z;
            return null;
        } catch (Exception e) {
            LOGGER.debug("Не удалось вернуть сущность {}: {}", ch.entityName(), e.getMessage());
            return "ошибка восстановления " + ch.entityName();
        }
    }

    /** Restore — обратная операция: сущность, возвращённую откатом, убираем снова. */
    @Nullable
    private static String removeOne(ServerLevel level, EntityType<?> type, RollbackData.EntityChange ch) {
        AABB box = new AABB(ch.x(), ch.y(), ch.z(), ch.x() + 1, ch.y() + 1, ch.z() + 1)
                .inflate(MATCH_RADIUS);
        List<? extends Entity> found = level.getEntities(type, box, e -> e.isAlive());
        if (found.isEmpty()) {
            return "нет " + ch.entityName() + " рядом с " + ch.x() + ", " + ch.y() + ", " + ch.z();
        }
        found.get(0).discard();
        return null;
    }

    private static ListTag doubles(double... values) {
        ListTag list = new ListTag();
        for (double v : values) list.add(DoubleTag.valueOf(v));
        return list;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) list.add(FloatTag.valueOf(v));
        return list;
    }
}
