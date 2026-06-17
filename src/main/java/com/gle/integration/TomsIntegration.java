package com.gle.integration;

/**
 * Модуль интеграции с Tom's Simple Storage (терминалы хранилища).
 * <p>
 * Логирование реализовано в {@code TomsTerminalLogger} и подключается миксинами
 * ({@code mixin/toms/*}, {@code require=0}), которые self-activate при наличии мода. Этот модуль —
 * точка модульного учёта/гейта в {@link IntegrationRegistry}.
 */
public final class TomsIntegration implements ModIntegration {

    @Override
    public String id() {
        return "Tom's Simple Storage";
    }

    @Override
    public String modId() {
        return "toms_storage";
    }

    @Override
    public void onActivate() {
        // Миксины Tom's активируются сами (require=0). Здесь — точка для будущей событийной обвязки.
    }
}
