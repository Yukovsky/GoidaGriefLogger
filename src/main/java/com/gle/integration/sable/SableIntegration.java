package com.gle.integration.sable;

import com.gle.GLEConfig;
import com.gle.integration.ModIntegration;

/**
 * Модуль интеграции с Sable — модом движущихся физических структур.
 * <p>
 * Своей логики почти не несёт: вся работа в миксинах, которые помечают поток атрибуцией
 * на время сборки, перемещения и разборки структуры. Модуль делает интеграцию явной —
 * присутствие мода, флаг конфига и запись в лог при старте.
 * <p>
 * Через ту же точку идут Create Aeronautics и Simulated: они миксинят в класс Sable.
 */
public final class SableIntegration implements ModIntegration {

    @Override
    public String id() {
        return "Sable";
    }

    @Override
    public String modId() {
        return "sable";
    }

    @Override
    public boolean isEnabled() {
        return GLEConfig.enableSableIntegration.get();
    }

    @Override
    public void onActivate() {
        // Миксины применяются загрузчиком сами (require=0) — точка для будущей обвязки.
    }
}
