package com.gle.integration;

import com.gle.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Обнаруживает доступные интеграции и активирует только присутствующие и включённые.
 * docs/06 §9: добавить новый мод = создать {@link ModIntegration} + mixin-конфиг и
 * зарегистрировать здесь — без изменений ядра и без касания других интеграций.
 */
public final class IntegrationRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger/Integrations");

    private final List<ModIntegration> registered = new ArrayList<>();
    private final List<ModIntegration> active = new ArrayList<>();

    public IntegrationRegistry register(ModIntegration integration) {
        registered.add(integration);
        return this;
    }

    /** Активировать все присутствующие и включённые интеграции. Идемпотентно по списку. */
    public void activateAll(Platform platform) {
        for (ModIntegration mod : registered) {
            if (!mod.isPresent(platform)) {
                LOGGER.debug("Интеграция {} пропущена: мод '{}' не загружен.", mod.id(), mod.modId());
                continue;
            }
            if (!mod.isEnabled()) {
                LOGGER.info("Интеграция {} отключена в конфиге.", mod.id());
                continue;
            }
            try {
                mod.onActivate();
                active.add(mod);
                LOGGER.info("Интеграция {} активна.", mod.id());
            } catch (Throwable t) {
                // Падение одной интеграции не должно валить мод.
                LOGGER.error("Не удалось активировать интеграцию {}", mod.id(), t);
            }
        }
    }

    public List<ModIntegration> active() {
        return List.copyOf(active);
    }
}
