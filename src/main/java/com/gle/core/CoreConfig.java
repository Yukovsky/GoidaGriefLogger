package com.gle.core;

import java.util.List;

/**
 * Платформо-нейтральный доступ к настройкам логирования, которые нужны ЯДРУ ({@code com.gle.core.*}).
 * <p>
 * Ядро не должно импортировать конфиг загрузчика ({@code com.gle.GLEConfig} тянет NeoForge
 * {@code ModConfigSpec}) — это нарушило бы правило §9 docs/06 («core без импортов loader/модов»)
 * и закрыло бы путь к Fabric. Поэтому платформенный слой строит реализацию этого интерфейса
 * (на NeoForge — поверх {@code GLEConfig}) и регистрирует её через {@link #set(CoreConfig)} в точке
 * входа мода. Ядро читает только {@link #get()}.
 * <p>
 * Значения читаются «вживую» (не снимок): чёрные списки могут меняться при перезагрузке конфига,
 * поэтому методы вызываются на каждое событие и возвращают актуальное состояние.
 * До инициализации действует {@link #DEFAULT} (всё разрешено, дефолтные лимиты) — это безопасно
 * для возможных ранних событий до старта сервера.
 */
public interface CoreConfig {

    /** Логировать ли активацию блоков (нажимные плиты/тропвайр), которую не пишет сам GriefLogger. */
    boolean blockActivationEnabled();

    /** Максимальный размер NBT BlockEntity для сохранения, КБ. */
    int maxNbtSizeKb();

    /** Измерения, которые не логируем. */
    List<? extends String> worldBlacklist();

    /** Источники ({@code source_type}), которые не логируем. */
    List<? extends String> sourceTypeBlacklist();

    /** Блоки/предметы (по id или нормализованному имени), которые не логируем. */
    List<? extends String> blockBlacklist();

    /** Modid целиком, которые не логируем. */
    List<? extends String> modBlacklist();

    // --- значения по умолчанию + глобальный holder (паттерн как у Platform) --------------------

    CoreConfig DEFAULT = new CoreConfig() {
        @Override public boolean blockActivationEnabled() { return true; }
        @Override public int maxNbtSizeKb() { return 512; }
        @Override public List<? extends String> worldBlacklist() { return List.of(); }
        @Override public List<? extends String> sourceTypeBlacklist() { return List.of(); }
        @Override public List<? extends String> blockBlacklist() { return List.of(); }
        @Override public List<? extends String> modBlacklist() { return List.of(); }
    };

    CoreConfig[] HOLDER = { DEFAULT };

    /** Зарегистрировать реализацию (из платформенного слоя). {@code null} возвращает к дефолту. */
    static void set(CoreConfig config) {
        HOLDER[0] = (config == null ? DEFAULT : config);
    }

    /** Текущая конфигурация ядра. Никогда не {@code null} (до инициализации — {@link #DEFAULT}). */
    static CoreConfig get() {
        return HOLDER[0];
    }
}
