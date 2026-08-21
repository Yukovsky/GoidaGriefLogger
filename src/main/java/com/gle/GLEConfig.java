package com.gle;

import com.gle.core.CoreConfig;
import com.gle.core.db.StorageSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Конфигурация GoidaGriefLogger (NeoForge native TOML): {@code config/goidagrieflogger-common.toml}.
 * <p>
 * После поглощения GriefLogger (Путь E) хранилище (SQLite/MySQL) настраивается ЗДЕСЬ —
 * мод владеет своей конфигурацией БД напрямую, без наследования от GL. Платформенный слой
 * собирает {@link StorageSettings} ({@link #storageSettings()}) и отдаёт в ядро.
 */
public final class GLEConfig {

    public static final ModConfigSpec SPEC;

    // [database] — собственное хранилище (раньше наследовалось от GriefLogger)
    public static final ModConfigSpec.BooleanValue useMysql;
    public static final ModConfigSpec.ConfigValue<String> mysqlHost;
    public static final ModConfigSpec.IntValue mysqlPort;
    public static final ModConfigSpec.ConfigValue<String> mysqlDatabase;
    public static final ModConfigSpec.ConfigValue<String> mysqlUsername;
    public static final ModConfigSpec.ConfigValue<String> mysqlPassword;
    public static final ModConfigSpec.IntValue mysqlTimeout;
    public static final ModConfigSpec.ConfigValue<String> sqliteFile;

    // [logging]
    public static final ModConfigSpec.BooleanValue enableExplosions;
    public static final ModConfigSpec.BooleanValue enablePistons;
    public static final ModConfigSpec.BooleanValue enableHoppers;
    public static final ModConfigSpec.BooleanValue enableItemPickup;
    public static final ModConfigSpec.BooleanValue enableContainerAccess;
    public static final ModConfigSpec.BooleanValue enableContainerTransactions;
    public static final ModConfigSpec.BooleanValue enableCarriedContainers;
    public static final ModConfigSpec.BooleanValue enableBlockActivation;
    public static final ModConfigSpec.BooleanValue enableModBlockChanges;
    public static final ModConfigSpec.BooleanValue enableEntityGriefing;
    public static final ModConfigSpec.BooleanValue enableGravityBlocks;
    public static final ModConfigSpec.BooleanValue enableSigns;
    public static final ModConfigSpec.BooleanValue enableItemFrames;
    public static final ModConfigSpec.BooleanValue enablePlayerDeath;
    public static final ModConfigSpec.BooleanValue enableFireSpread;
    public static final ModConfigSpec.BooleanValue enableLavaFlow;
    public static final ModConfigSpec.BooleanValue enableWaterFlow;
    public static final ModConfigSpec.BooleanValue enableSculk;
    public static final ModConfigSpec.BooleanValue enableIceSnow;

    // [performance]
    public static final ModConfigSpec.IntValue maxExplosionBlocks;
    public static final ModConfigSpec.IntValue asyncQueueSize;
    public static final ModConfigSpec.IntValue deduplicationWindowMs;
    public static final ModConfigSpec.IntValue maxNbtSizeKb;
    public static final ModConfigSpec.IntValue environmentalRateLimitPerBlockSec;

    // [rollback]
    public static final ModConfigSpec.BooleanValue restoreEntities;
    public static final ModConfigSpec.IntValue batchSize;
    public static final ModConfigSpec.IntValue progressIntervalTicks;
    public static final ModConfigSpec.IntValue maxRestoreAgeDays;
    public static final ModConfigSpec.IntValue maxPreviewDurationSec;
    public static final ModConfigSpec.IntValue maxRollbackRows;
    public static final ModConfigSpec.IntValue previewAutoCancelBlocks;

    // [integrations.create] / [integrations.toms] / [integrations.backpacks]
    public static final ModConfigSpec.BooleanValue enableCreateIntegration;
    public static final ModConfigSpec.BooleanValue enableTomsIntegration;
    public static final ModConfigSpec.BooleanValue enableSableIntegration;
    public static final ModConfigSpec.BooleanValue enableBackpacksIntegration;
    // [integrations] universal item tracking (опционально, экспериментально)
    public static final ModConfigSpec.BooleanValue universalItemTracking;

    // [blacklists]
    public static final ModConfigSpec.ConfigValue<List<? extends String>> worldBlacklist;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> sourceTypeBlacklist;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> blockBlacklist;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> modBlacklist;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> entityTypeBlacklist;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Хранилище GoidaGriefLogger (единственный писатель). Раньше наследовалось от GriefLogger.").push("database");
        useMysql      = b.comment("Использовать MySQL/MariaDB (true) или SQLite (false)").define("useMysql", false);
        mysqlHost     = b.comment("Хост MySQL").define("mysqlHost", "localhost");
        mysqlPort     = b.comment("Порт MySQL").defineInRange("mysqlPort", 3306, 1, 65535);
        mysqlDatabase = b.comment("Имя базы MySQL").define("mysqlDatabase", "database");
        mysqlUsername = b.comment("Пользователь MySQL").define("mysqlUsername", "username");
        mysqlPassword = b.comment("Пароль MySQL").define("mysqlPassword", "password");
        mysqlTimeout  = b.comment("Таймаут соединения MySQL (мс)").defineInRange("mysqlTimeout", 5000, 1, 60_000);
        sqliteFile    = b.comment("Файл SQLite (относительно рабочей директории сервера).",
                "По умолчанию database.db — тот же файл, что использовал GriefLogger, чтобы старые данные читались.")
                .define("sqliteFile", "database.db");
        b.pop();

        b.comment("Что логировать").push("logging");
        enableExplosions     = b.comment("Взрывы (TNT, крипер, кристалл Края и т.д.)").define("enableExplosions", true);
        enablePistons        = b.comment("Перемещения и разрушения пистонами").define("enablePistons", true);
        enableHoppers        = b.comment("Переносы предметов хопперами (много событий!).",
                "Реализуется через перехват capability ItemHandler, поэтому источник пишется как [AUTO]:",
                "ванильный путь HopperBlockEntity.addItem в NeoForge практически не достигается.")
                .define("enableHoppers", false);
        enableItemPickup     = b.comment("Подбор предметов игроком с земли (записывается в таблицу items GL).",
                "GriefLogger 1.21.1 не логирует подбор сам (его architectury-мост к событию подбора не работает),",
                "поэтому это делает GLE. Выключите, если у вас GL уже пишет подбор (во избежание дублей).")
                .define("enableItemPickup", true);
        enableContainerAccess= b.comment("Клик (открытие) по хранилищам/терминалам из модов — Tom's Storage,",
                "Sophisticated, ящики-моды, Create-вместилища и т.п. (action INTERACT, как у сундука).",
                "Ванильные блоки логирует сам GriefLogger, поэтому здесь только не-minecraft блоки.")
                .define("enableContainerAccess", true);
        enableContainerTransactions = b.comment(
                "Что игрок взял/положил в модовые хранилища на capability (Sophisticated Backpacks как блок,",
                "ящики-моды, Create-вместилища) — снимок при открытии, разница при закрытии (как у сундука).",
                "Ванильные контейнеры (BaseContainerBlockEntity) логирует сам GriefLogger — их не дублируем.")
                .define("enableContainerTransactions", true);
        enableCarriedContainers = b.comment(
                "Транзакции в НОСИМЫХ вместилищах — рюкзаках и сумках, открываемых из инвентаря.",
                "У них нет позиции в мире, поэтому обычный контейнерный слой их не видит вообще.",
                "Запись идёт по позиции игрока. Работает для любых модов рюкзаков.")
                .define("enableCarriedContainers", true);
        enableBlockActivation= b.comment("Активация блоков, которую НЕ пишет сам GriefLogger: нажимные плиты,",
                "тропвайр (срабатывают наступанием, без права-клика). Кнопки/рычаги/двери/люки/калитки",
                "и репитеры уже логирует GriefLogger по right-click — их не дублируем.",
                "Может быть шумно на редстоун-фермах — при необходимости добавьте 'activate' в sourceTypeBlacklist.")
                .define("enableBlockActivation", true);
        enableModBlockChanges= b.comment("Любые изменения блоков не-игроками (моды, мобы, fake-players)").define("enableModBlockChanges", true);
        enableEntityGriefing = b.comment("Гриефинг мобами (эндермен, зомби и т.д.)").define("enableEntityGriefing", true);
        enableGravityBlocks  = b.comment("Гравитационные блоки (песок, гравий)").define("enableGravityBlocks", true);
        enableSigns          = b.comment("Изменение текста табличек (Фаза 2)").define("enableSigns", false);
        enableItemFrames     = b.comment("Рамки и картины (Фаза 2)").define("enableItemFrames", false);
        enablePlayerDeath    = b.comment("Инвентарь игрока при смерти (Фаза 2)").define("enablePlayerDeath", false);
        enableFireSpread     = b.comment("Огонь: распространение/выгорание (Фаза 3, МНОГО событий)").define("enableFireSpread", false);
        enableLavaFlow       = b.comment("Растекание лавы (Фаза 3)").define("enableLavaFlow", false);
        enableWaterFlow      = b.comment("Растекание воды (Фаза 3)").define("enableWaterFlow", false);
        enableSculk          = b.comment("Распространение скалка (Фаза 3)").define("enableSculk", false);
        enableIceSnow        = b.comment("Лёд и снег: образование/таяние (Фаза 3)").define("enableIceSnow", false);
        b.pop();

        b.comment("Производительность").push("performance");
        maxExplosionBlocks   = b.comment(
                "Макс. блоков, логируемых на ОДИН взрыв. Всё сверх лимита не попадёт в лог",
                "и не может быть откачено, поэтому занижать опасно: крупный заряд или снаряд",
                "пушки легко перекрывает несколько сотен блоков.")
                .defineInRange("maxExplosionBlocks", 10_000, 1, 1_000_000);
        asyncQueueSize       = b.comment("Размер очереди записи до throttling").defineInRange("asyncQueueSize", 10_000, 256, 1_000_000);
        deduplicationWindowMs= b.comment("Окно дедупликации событий (мс)").defineInRange("deduplicationWindowMs", 100, 0, 10_000);
        maxNbtSizeKb         = b.comment("Макс. размер NBT BlockEntity для сохранения (КБ)").defineInRange("maxNbtSizeKb", 512, 0, 16_384);
        environmentalRateLimitPerBlockSec = b.comment("Rate-limit fire/lava/water: секунд между повторами на блок")
                .defineInRange("environmentalRateLimitPerBlockSec", 5, 0, 3600);
        b.pop();

        b.comment("Роллбек / restore / preview").push("rollback");
        restoreEntities       = b.comment("Возвращать убитых сущностей при откате.",
                "Снимок хранится только у особей, отличающихся от обычных (имя, экипировка, атрибуты),",
                "поэтому объём в БД от этого почти не растёт.")
                .define("restoreEntities", true);
        batchSize             = b.comment("Блоков за тик").defineInRange("batchSize", 200, 50, 1000);
        progressIntervalTicks = b.comment("Тиков между сообщениями прогресса").defineInRange("progressIntervalTicks", 20, 1, 200);
        maxRestoreAgeDays     = b.comment("Макс. возраст роллбека для /gl restore (дни)").defineInRange("maxRestoreAgeDays", 7, 1, 365);
        maxPreviewDurationSec = b.comment("Время жизни preview (сек)").defineInRange("maxPreviewDurationSec", 60, 5, 600);
        previewAutoCancelBlocks = b.comment("Дистанция авто-отмены preview (блоки)").defineInRange("previewAutoCancelBlocks", 50, 8, 256);
        maxRollbackRows       = b.comment(
                "Макс. строк, вычитываемых из БД за ОДИН откат/restore/preview. Широкая команда",
                "(r:global t:30d) иначе тянет в память всю подходящую историю вместе со снимками NBT.",
                "Лишнее отсекается с ХВОСТА выборки (самое старое при откате), о срезе пишется",
                "предупреждение в лог сервера — сузьте радиус или окно и повторите.")
                .defineInRange("maxRollbackRows", 300_000, 1_000, 10_000_000);
        b.pop();

        b.comment("Интеграции").push("integrations");
        universalItemTracking = b.comment(
                "ЭКСПЕРИМЕНТАЛЬНО. Логировать ЛЮБОЕ перемещение предметов через capability IItemHandler",
                "(воронки/ленты/жёлоба/насосы Create, Tom's Simple Storage, Create Vibrant Vaults,",
                "Create Contraption Terminals и любые моды-автоматизации). Источник: [AUTO].",
                "Может конфликтовать с модами, делающими instanceof своих хендлеров. Включайте осознанно.")
                .define("universalItemTracking", false);
        b.push("create");
        enableCreateIntegration = b.comment("Логировать изменения блоков от Create (контрапции, схематическая пушка)")
                .define("enabled", true);
        b.pop();
        b.push("toms");
        enableTomsIntegration = b.comment("Атрибутировать перемещения через терминалы Tom's Simple Storage реальному игроку",
                "(иначе они попадут в историю только как [AUTO] через универсальный трекинг).")
                .define("enabled", true);
        b.pop();
        b.push("sable");
        enableSableIntegration = b.comment(
                "Логировать сборку, перемещение и разборку физических структур Sable.",
                "Покрывает также Create Aeronautics и Simulated — они идут через тот же класс Sable.",
                "Без этого целая постройка, поднятая в физическую структуру, исчезает из лога бесследно.")
                .define("enabled", true);
        b.pop();
        b.push("backpacks");
        enableBackpacksIntegration = b.comment("Учитывать Sophisticated Backpacks как отдельную интеграцию.",
                "Покрытие поставленных рюкзаков-блоков идёт через universal/container tracking;",
                "флаг включает явную регистрацию модуля и его лог при старте.")
                .define("enabled", true);
        b.pop(2);

        b.comment("Чёрные списки").push("blacklists");
        worldBlacklist      = b.comment("Измерения").defineListAllowEmpty("worldBlacklist", List.of(), GLEConfig::isString);
        sourceTypeBlacklist = b.comment("Источники (source_type), которые НЕ логировать даже если категория включена.",
                "ВНИМАНИЕ: значения здесь перекрывают флаги enable* выше. Пусто по умолчанию.",
                "Пример значений: water, lava, fire, gravity, sculk, melting, hopper, piston.")
                .defineListAllowEmpty("sourceTypeBlacklist", List.of(), GLEConfig::isString);
        blockBlacklist      = b.comment("Блоки").defineListAllowEmpty("blockBlacklist", List.of(), GLEConfig::isString);
        modBlacklist        = b.comment("Modid целиком").defineListAllowEmpty("modBlacklist", List.of(), GLEConfig::isString);
        entityTypeBlacklist = b.comment("Типы сущностей").defineListAllowEmpty("entityTypeBlacklist", List.of(), GLEConfig::isString);
        b.pop();

        SPEC = b.build();
    }

    private static boolean isString(Object o) {
        return o instanceof String;
    }

    /**
     * Платформо-нейтральная обёртка настроек логирования для ЯДРА ({@link CoreConfig}).
     * Читает значения «вживую» (на каждый вызов), чтобы перезагрузка конфига подхватывалась.
     * Регистрируется в точке входа мода через {@code CoreConfig.set(...)} — после этого ядро
     * не зависит от {@code GLEConfig}/NeoForge (правило §9 docs/06).
     */
    public static CoreConfig coreConfig() {
        return new CoreConfig() {
            @Override public boolean blockActivationEnabled() { return enableBlockActivation.get(); }
            @Override public int maxNbtSizeKb() { return maxNbtSizeKb.get(); }
            @Override public int maxRollbackRows() { return maxRollbackRows.get(); }
            @Override public List<? extends String> worldBlacklist() { return worldBlacklist.get(); }
            @Override public List<? extends String> sourceTypeBlacklist() { return sourceTypeBlacklist.get(); }
            @Override public List<? extends String> blockBlacklist() { return blockBlacklist.get(); }
            @Override public List<? extends String> modBlacklist() { return modBlacklist.get(); }
        };
    }

    /**
     * Снять текущие настройки хранилища в платформо-нейтральный {@link StorageSettings} для ядра.
     * Вызывать после загрузки конфига (на старте сервера).
     */
    public static StorageSettings storageSettings() {
        return new StorageSettings(
                useMysql.get(),
                mysqlHost.get(),
                mysqlPort.get(),
                mysqlDatabase.get(),
                mysqlUsername.get(),
                mysqlPassword.get(),
                mysqlTimeout.get(),
                sqliteFile.get()
        );
    }

    private GLEConfig() {}
}
