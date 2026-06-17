package com.gle.platform.neoforge;

import com.gle.platform.Platform;
import net.neoforged.fml.ModList;

/**
 * Реализация {@link Platform} для NeoForge. Единственное место, где платформенный модуль
 * касается {@code net.neoforged.*} от имени ядра/реестра интеграций.
 */
public final class NeoForgePlatform implements Platform {

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get() != null && ModList.get().isLoaded(modId);
    }
}
