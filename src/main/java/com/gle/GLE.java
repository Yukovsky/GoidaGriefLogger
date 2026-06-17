package com.gle;

import com.gle.api.GLExtended;
import com.gle.api.GLExtendedApiImpl;
import com.gle.command.GLCommand;
import com.gle.db.GLStorage;
import com.gle.integration.CreateIntegration;
import com.gle.integration.IntegrationRegistry;
import com.gle.integration.TomsIntegration;
import com.gle.listener.ContainerAccessListener;
import com.gle.listener.ContainerTransactionListener;
import com.gle.listener.DecorationListener;
import com.gle.listener.ExplosionListener;
import com.gle.listener.ItemPickupListener;
import com.gle.listener.ModBlockListener;
import com.gle.listener.PistonListener;
import com.gle.listener.PlayerBlockListener;
import com.gle.listener.PlayerDeathListener;
import com.gle.listener.PlayerItemListener;
import com.gle.listener.SessionListener;
import com.gle.platform.Platform;
import com.gle.platform.neoforge.NeoForgePlatform;
import com.gle.rollback.PreviewManager;
import com.gle.rollback.RollbackManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Главный класс мода GoidaGriefLogger.
 * <p>
 * Путь E («единый писатель»): GoidaGriefLogger — это форк и поглощение GriefLogger. Один мод,
 * одно соединение, один {@code WriteQueue} на все события (игровые и не-игровые). GriefLogger
 * с сервера убирается полностью; конкуренция двух писателей за справочники с FK невозможна
 * в принципе. Содержит логирование, роллбек/restore/preview и единую команду {@code /gl}.
 * <p>
 * Производное от GriefLogger (Apache-2.0, daqem) — см. {@code LICENSE} и {@code NOTICE}.
 */
@Mod(GLE.MOD_ID)
public final class GLE {

    public static final String MOD_ID = "goidagrieflogger";
    public static final Logger LOGGER = LoggerFactory.getLogger("GoidaGriefLogger");

    /** Реестр подключаемых интеграций модов (Create, Tom's, …). Активируется на старте сервера. */
    private final IntegrationRegistry integrations = new IntegrationRegistry();

    public GLE(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, GLEConfig.SPEC, "goidagrieflogger-common.toml");

        // Граница «ядро ↔ загрузчик»: ставим платформу до всего остального.
        Platform.set(new NeoForgePlatform());

        // Публичный API
        GLExtended.setApi(new GLExtendedApiImpl());

        // Игровые события (жизненный цикл сервера + старт/стоп хранилища)
        NeoForge.EVENT_BUS.register(this);

        // Игровые события мира/игроков — теперь пишет сам мод (поглощение GL, Путь E)
        NeoForge.EVENT_BUS.register(new SessionListener());
        NeoForge.EVENT_BUS.register(new PlayerBlockListener());
        NeoForge.EVENT_BUS.register(new PlayerItemListener());

        // Не-игровые источники
        NeoForge.EVENT_BUS.register(new ExplosionListener());
        NeoForge.EVENT_BUS.register(new PistonListener());
        NeoForge.EVENT_BUS.register(new ModBlockListener());
        NeoForge.EVENT_BUS.register(new PlayerDeathListener());
        NeoForge.EVENT_BUS.register(new DecorationListener());
        NeoForge.EVENT_BUS.register(new ItemPickupListener());
        NeoForge.EVENT_BUS.register(new ContainerAccessListener());
        NeoForge.EVENT_BUS.register(new ContainerTransactionListener());

        // Подключаемые модули интеграций (активируются по наличию modid на старте сервера)
        integrations.register(new CreateIntegration())
                    .register(new TomsIntegration());

        LOGGER.info("GoidaGriefLogger загружен. Ожидание старта сервера для подключения к БД.");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        boolean ok = GLStorage.init(GLEConfig.storageSettings(), GLEConfig.asyncQueueSize.get());
        if (ok) {
            LOGGER.info("Хранилище подключено, логирование активно.");
        } else {
            LOGGER.error("Хранилище недоступно — все листенеры будут молча пропускать запись.");
        }
        Platform platform = Platform.get();
        if (platform != null) {
            integrations.activateAll(platform);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        GLStorage.shutdown();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        GLCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        RollbackManager.get().tick();
        PreviewManager.get().tick(event.getServer());
    }
}
