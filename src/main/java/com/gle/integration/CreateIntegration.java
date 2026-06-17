package com.gle.integration;

import com.gle.GLEConfig;

/**
 * Модуль интеграции с Create (контрапции, схематическая пушка, Mechanical Arm, вместилища).
 * <p>
 * Логика логирования уже реализована в {@code CreateLogger}/{@code CreateItemLogger} и
 * подключается миксинами Create ({@code mixin/create/*}, {@code require=0}) — они self-activate
 * при наличии мода. Этот модуль — точка модульного учёта/гейта в {@link IntegrationRegistry}:
 * присутствие мода + флаг конфига. Дальнейший перенос обвязки сюда — Фаза 1.
 */
public final class CreateIntegration implements ModIntegration {

    @Override
    public String id() {
        return "Create";
    }

    @Override
    public String modId() {
        return "create";
    }

    @Override
    public boolean isEnabled() {
        return GLEConfig.enableCreateIntegration.get();
    }

    @Override
    public void onActivate() {
        // Миксины Create активируются сами (require=0). Здесь — точка для будущей событийной обвязки.
    }
}
