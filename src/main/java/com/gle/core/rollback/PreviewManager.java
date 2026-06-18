package com.gle.core.rollback;

import com.gle.GLEConfig;
import com.gle.core.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * REQ-PREVIEW-001: предпросмотр роллбека через клиентские пакеты обновления блоков
 * без изменения мира. Каждому затронутому блоку игроку шлётся фейковое состояние;
 * отмена/таймаут/перемещение возвращают настоящие состояния.
 */
public final class PreviewManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/Preview");
    private static final PreviewManager INSTANCE = new PreviewManager();

    public static PreviewManager get() { return INSTANCE; }

    private static final class Session {
        final ServerLevel level;
        final Map<BlockPos, BlockState> realStates; // для отката пакетов
        final long startedAt;
        final BlockPos origin;
        Session(ServerLevel level, Map<BlockPos, BlockState> realStates, BlockPos origin) {
            this.level = level;
            this.realStates = realStates;
            this.startedAt = System.currentTimeMillis();
            this.origin = origin;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GLE-preview"); t.setDaemon(true); return t;
    });

    private PreviewManager() {}

    /** Асинхронный предпросмотр: выборка в фоне, рассылка пакетов — на главном потоке. */
    public void start(ServerPlayer player, RollbackFilter filter, Consumer<Component> out) {
        if (!GLStorage.isReady()) { out.accept(Component.literal("§cХранилище недоступно.")); return; }
        cancel(player); // снять предыдущий preview
        MinecraftServer server = player.server;

        dbExecutor.submit(() -> {
            List<RollbackData.BlockChange> blocks;
            try (Connection conn = GLStorage.get().database().newConnection()) {
                blocks = RollbackData.queryBlocks(conn, filter);
            } catch (Exception e) {
                server.execute(() -> out.accept(Component.literal("§c" + RollbackManager.translateDbError(e))));
                return;
            }
            final List<RollbackData.BlockChange> fBlocks = blocks;
            server.execute(() -> applyPreview(player, fBlocks, out));
        });
    }

    /** Главный поток: вычисляем состояния, шлём пакеты, сохраняем сессию. */
    private void applyPreview(ServerPlayer player, List<RollbackData.BlockChange> blocks, Consumer<Component> out) {
        if (blocks.isEmpty()) { out.accept(Component.literal("§7[Preview] Нет изменений по фильтру.")); return; }
        ServerLevel level = (ServerLevel) player.level();

        // Reverse-chrono: первая встреченная позиция определяет итоговое состояние.
        Map<BlockPos, BlockState> preview = new LinkedHashMap<>();
        for (RollbackData.BlockChange ch : blocks) {
            BlockPos pos = new BlockPos(ch.x(), ch.y(), ch.z());
            if (preview.containsKey(pos)) continue;
            BlockState state = BlockRestorer.computeReverseState(level, ch);
            if (state != null) preview.put(pos, state);
        }

        Map<BlockPos, BlockState> real = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : preview.entrySet()) {
            real.put(e.getKey(), level.getBlockState(e.getKey()));
            player.connection.send(new ClientboundBlockUpdatePacket(e.getKey(), e.getValue()));
        }
        sessions.put(player.getUUID(), new Session(level, real, player.blockPosition()));
        out.accept(Component.literal("§b[Preview] Затронуто блоков: " + preview.size()
                + ". §7Используйте /gl rollback ... для применения или /gl preview cancel."));
    }

    public boolean cancel(ServerPlayer player) {
        Session s = sessions.remove(player.getUUID());
        if (s == null) return false;
        revert(player, s);
        return true;
    }

    private void revert(ServerPlayer player, Session s) {
        for (Map.Entry<BlockPos, BlockState> e : s.realStates.entrySet()) {
            player.connection.send(new ClientboundBlockUpdatePacket(e.getKey(), e.getValue()));
        }
    }

    /** Вызывается каждый тик: авто-отмена по таймауту/перемещению/смене измерения. */
    public void tick(net.minecraft.server.MinecraftServer server) {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        long maxMs = GLEConfig.maxPreviewDurationSec.get() * 1000L;
        int maxDist = GLEConfig.previewAutoCancelBlocks.get();
        sessions.forEach((uuid, s) -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null) { sessions.remove(uuid); return; }
            boolean expired = now - s.startedAt > maxMs;
            boolean movedDim = p.level() != s.level;
            boolean movedFar = p.blockPosition().distManhattan(s.origin) > maxDist;
            if (expired || movedDim || movedFar) {
                sessions.remove(uuid);
                if (!movedDim) revert(p, s);
                p.sendSystemMessage(Component.literal("§7[Preview] Отменён."));
            }
        });
    }
}
