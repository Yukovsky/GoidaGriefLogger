package com.gle.integration;

import com.gle.platform.Platform;

/**
 * Контракт подключаемого модуля интеграции с конкретным модом (Create, Tom's Storage,
 * Sophisticated Backpacks, …). docs/06 §9: каждая интеграция — самодостаточный модуль,
 * активируется {@link IntegrationRegistry} ТОЛЬКО если её мод присутствует и она включена
 * в конфиге. Ядро о конкретных модах ничего не знает.
 */
public interface ModIntegration {

    /** Человекочитаемый идентификатор для логов («Create»). */
    String id();

    /** modid мода, по наличию которого активируется интеграция. */
    String modId();

    /** Включена ли интеграция в конфиге (помимо наличия мода). По умолчанию — да. */
    default boolean isEnabled() {
        return true;
    }

    /** Присутствует ли мод в рантайме. */
    default boolean isPresent(Platform platform) {
        return platform.isModLoaded(modId());
    }

    /**
     * Активировать интеграцию: подписать слушатели, включить флаги. Вызывается один раз на старте,
     * только если {@link #isPresent(Platform)} и {@link #isEnabled()}. Миксины мода подключаются
     * отдельно (свой mixin-конфиг с {@code require=0}), поэтому здесь — только событийная обвязка.
     */
    void onActivate();
}
