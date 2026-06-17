package com.gle.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Определяет источник изменения мира по сущности-инициатору.
 * Возвращает {@link Resolved}: строку {@code source_type} и имя системного пользователя
 * для атрибуции в таблице {@code blocks} (см. {@link SystemUsers}).
 */
public final class GLESourceResolver {

    private GLESourceResolver() {}

    public record Resolved(String sourceType, String systemUser) {}

    public static final Resolved UNKNOWN = new Resolved(SourceType.UNKNOWN, SystemUsers.SERVER);

    public static Resolved resolve(@Nullable Entity entity) {
        if (entity == null) {
            return UNKNOWN;
        }

        // Create Deployer и прочие fake-players (это подклассы ServerPlayer!)
        if (entity instanceof FakePlayer fake) {
            String name = fake.getClass().getName();
            if (name.contains("Deployer")) {
                return new Resolved(SourceType.CREATE_DEPLOYER, SystemUsers.CREATE);
            }
            if (name.contains("Schematicannon")) {
                return new Resolved(SourceType.CREATE_SCHEMATICANNON, SystemUsers.CREATE);
            }
            String modid = modIdOf(entity);
            return new Resolved(modid + ":fakeplayer", "create".equals(modid) ? SystemUsers.CREATE : SystemUsers.SERVER);
        }

        if (entity instanceof FallingBlockEntity) {
            return new Resolved(SourceType.GRAVITY, SystemUsers.GRAVITY);
        }

        if (entity instanceof AbstractHurtingProjectile) {
            return new Resolved("projectile:" + entityTypeId(entity), SystemUsers.MOB);
        }

        if (entity instanceof Mob) {
            return new Resolved(SourceType.entity(entityTypePath(entity)), SystemUsers.MOB);
        }

        // Универсальный fallback: <modid>:<entity_type>
        String modid = modIdOf(entity);
        String user = "create".equals(modid) ? SystemUsers.CREATE : SystemUsers.SERVER;
        return new Resolved(modid + ":" + entityTypePath(entity), user);
    }

    private static ResourceLocation entityKey(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    }

    public static String modIdOf(Entity entity) {
        ResourceLocation key = entityKey(entity);
        return key == null ? "unknown" : key.getNamespace();
    }

    public static String entityTypePath(Entity entity) {
        ResourceLocation key = entityKey(entity);
        return key == null ? "unknown" : key.getPath();
    }

    public static String entityTypeId(Entity entity) {
        ResourceLocation key = entityKey(entity);
        return key == null ? "unknown" : key.toString();
    }
}
