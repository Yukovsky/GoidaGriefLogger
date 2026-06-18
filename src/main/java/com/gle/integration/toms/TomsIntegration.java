package com.gle.integration.toms;

import com.gle.GLEConfig;
import com.gle.integration.ModIntegration;

/**
 * Модуль интеграции с Tom's Simple Storage (терминалы хранилища).
 * Самодостаточный пакет {@code integration/toms}: контекст {@link TomsContext} + логгер
 * {@link TomsTerminalLogger} + миксины {@code integration/toms/mixin/*} (отдельный конфиг
 * {@code gle.toms.mixins.json}, {@code require=0}/{@code remap=false} → no-op без Tom's).
 * <p>
 * Точка учёта/гейта в {@code IntegrationRegistry}: присутствие мода + флаг конфига
 * {@code integrations.toms.enabled}. Миксины применяются загрузчиком сами и дополнительно сверяются
 * с этим флагом, чтобы атрибуцию игроку можно было отключить, не трогая универсальный трекинг.
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
    public boolean isEnabled() {
        return GLEConfig.enableTomsIntegration.get();
    }

    @Override
    public void onActivate() {
        // Миксины Tom's активируются сами (require=0). Здесь — точка для будущей событийной обвязки.
    }
}
