package com.gle.integration.create;

import com.gle.GLEConfig;
import com.gle.integration.ModIntegration;

/**
 * Модуль интеграции с Create (контрапции, схематическая пушка, Mechanical Arm, вместилища).
 * Самодостаточный пакет {@code integration/create}: логгер {@link CreateItemLogger} + миксины
 * {@code integration/create/mixin/*} (отдельный конфиг {@code gle.create.mixins.json},
 * {@code require=0}/{@code remap=false} → no-op без Create). Изменения блоков от контрапций/пушки
 * атрибутируются через ядро-нейтральный {@code GriefContext}, поэтому ядро о Create не знает (§9).
 * <p>
 * Этот класс — точка учёта/гейта в {@code IntegrationRegistry}: присутствие мода + флаг конфига.
 * Миксины применяются загрузчиком самостоятельно; {@link #onActivate()} — для будущей событийной
 * обвязки на шине NeoForge (для Create её сейчас нет — всё через миксины/capability).
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
