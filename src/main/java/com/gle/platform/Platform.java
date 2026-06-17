package com.gle.platform;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Граница между платформо-нейтральным ядром ({@code com.gle.core.*}) и загрузчиком модов.
 * <p>
 * Ядро и реестр интеграций импортируют ТОЛЬКО этот интерфейс — не {@code net.neoforged.*} и
 * не {@code net.fabricmc.*}. Реализация живёт в {@code com.gle.platform.<loader>}
 * ({@link com.gle.platform.neoforge.NeoForgePlatform} сейчас; Fabric — отдельным модулем потом,
 * без правки ядра). Это одно из двух измерений модульности из docs/06 §9.
 */
public interface Platform {

    /** Имя загрузчика для логов («NeoForge», «Fabric»). */
    String loaderName();

    /** Загружен ли мод с данным {@code modId} в текущем рантайме. Гейт активации интеграций. */
    boolean isModLoaded(String modId);

    /**
     * Является ли сущность fake-player'ом (Create Deployer/Schematicannon, автоматизация и т.п.).
     * Платформо-специфично: NeoForge {@code FakePlayer} ↔ Fabric {@code FakePlayer}. Ядро спрашивает
     * через {@link #isFake(Entity)}, не импортируя классы загрузчика.
     */
    boolean isFakePlayer(Entity entity);

    // --- глобальный holder ----------------------------------------------------

    @Nullable
    Platform[] HOLDER = new Platform[1];

    static void set(Platform platform) {
        HOLDER[0] = platform;
    }

    /** Текущая платформа. {@code null} до инициализации в точке входа мода. */
    @Nullable
    static Platform get() {
        return HOLDER[0];
    }

    /** Null-безопасная проверка fake-player через текущую платформу. До инициализации — {@code false}. */
    static boolean isFake(Entity entity) {
        Platform p = get();
        return p != null && p.isFakePlayer(entity);
    }
}
