package com.gle.integration.backpacks;

import com.gle.GLE;
import com.gle.GLEConfig;
import com.gle.integration.ModIntegration;

/**
 * Модуль интеграции с Sophisticated Backpacks.
 * <p>
 * В отличие от Create/Tom's у рюкзаков НЕТ собственных миксинов: поставленный рюкзак-блок
 * отдаёт {@code IItemHandler} как capability, поэтому его содержимое и перемещения уже покрыты
 * универсальными механизмами ядра/обвязки — {@code BlockCapabilityMixin}+{@code AutomationItemLogger}
 * (опция {@code universalItemTracking}), {@code ContainerAccessListener} (INTERACT) и
 * {@code ContainerTransactionListener} (снимок/разница содержимого). Этот класс делает интеграцию
 * <em>явной</em>: отдельный модуль за {@link ModIntegration} с собственным флагом и регистрацией в
 * {@code IntegrationRegistry}, активируемый только при наличии мода (docs/06 §1, §9).
 * <p>
 * Известное ограничение: рюкзак, открытый из инвентаря игрока (а не поставленный блоком), идёт не
 * через capability блока — его точечное логирование потребует отдельного миксина на меню рюкзака
 * (будущая работа). Здесь модуль лишь фиксирует присутствие и факт покрытия общими механизмами.
 */
public final class BackpacksIntegration implements ModIntegration {

    @Override
    public String id() {
        return "Sophisticated Backpacks";
    }

    @Override
    public String modId() {
        return "sophisticatedbackpacks";
    }

    @Override
    public boolean isEnabled() {
        return GLEConfig.enableBackpacksIntegration.get();
    }

    @Override
    public void onActivate() {
        // Своих миксинов нет: поставленные рюкзаки-блоки покрыты универсальным трекингом capability
        // и контейнерными слушателями. Модуль — явная точка учёта/гейта.
        GLE.LOGGER.info("Sophisticated Backpacks: покрытие через universal/container tracking "
                + "(поставленные рюкзаки-блоки). universalItemTracking={}",
                GLEConfig.universalItemTracking.get());
    }
}
