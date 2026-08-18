package com.gle.core;

import com.gle.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
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
        if (Platform.isFake(entity)) {
            String name = entity.getClass().getName();
            if (name.contains("Deployer")) {
                return new Resolved(SourceType.CREATE_DEPLOYER, SystemUsers.CREATE);
            }
            if (name.contains("Schematicannon")) {
                return new Resolved(SourceType.CREATE_SCHEMATICANNON, SystemUsers.CREATE);
            }
            // modIdOf здесь бесполезен: FakePlayer — это EntityType.PLAYER, поэтому он ВСЕГДА
            // возвращал "minecraft", ветка "create" была недостижима, а реальный автор терялся.
            // Класс fake-player'а несёт имя своего мода, из него и берём modid.
            String modid = modIdOfClass(entity);
            return new Resolved(modid + ":fakeplayer",
                    "create".equals(modid) ? SystemUsers.CREATE : SystemUsers.SERVER);
        }

        if (entity instanceof FallingBlockEntity) {
            return new Resolved(SourceType.GRAVITY, SystemUsers.GRAVITY);
        }

        if (entity instanceof AbstractHurtingProjectile) {
            return new Resolved("projectile:" + entityTypeId(entity), SystemUsers.MOB);
        }

        if (entity instanceof Mob) {
            // Namespace сохраняем, как в соседних ветках: иначе мобы двух модов с одинаковым
            // path схлопывались в один source_type.
            return new Resolved(SourceType.entity(entityTypeId(entity)), SystemUsers.MOB);
        }

        // Универсальный fallback: <modid>:<entity_type>
        String modid = modIdOf(entity);
        String user = "create".equals(modid) ? SystemUsers.CREATE : SystemUsers.SERVER;
        return new Resolved(modid + ":" + entityTypePath(entity), user);
    }

    private static ResourceLocation entityKey(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    }

    /**
     * modid по ПАКЕТУ класса сущности. Нужен для fake-player'ов: их {@code EntityType} всегда
     * {@code minecraft:player}, поэтому реестр про их мод ничего не знает.
     * Например {@code com.simibubi.create.…} → {@code create}, {@code dev.foo.bar.…} → {@code bar}.
     */
    public static String modIdOfClass(Entity entity) {
        String pkg = entity.getClass().getName();
        String[] parts = pkg.split("\\.");
        for (String part : parts) {
            // Пропускаем типовые префиксы доменов; первый содержательный сегмент и есть modid.
            if (part.equals("com") || part.equals("net") || part.equals("org") || part.equals("dev")
                    || part.equals("io") || part.equals("me")) continue;
            return part.toLowerCase(java.util.Locale.ROOT);
        }
        return "unknown";
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
