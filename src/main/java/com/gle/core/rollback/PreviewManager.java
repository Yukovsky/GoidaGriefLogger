package com.gle.core.rollback;

import com.gle.GLEConfig;
import com.gle.core.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
        /** Границы области фильтра — по ним рисуется рамка из партиклов. */
        final BlockPos boxMin, boxMax;
        Session(ServerLevel level, Map<BlockPos, BlockState> realStates, BlockPos origin,
                BlockPos boxMin, BlockPos boxMax) {
            this.level = level;
            this.realStates = realStates;
            this.startedAt = System.currentTimeMillis();
            this.origin = origin;
            this.boxMin = boxMin;
            this.boxMax = boxMax;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GLE-preview"); t.setDaemon(true); return t;
    });

    /** Как часто перерисовывать рамку (тиков). Партикл живёт дольше, мигания нет. */
    private static final int OUTLINE_INTERVAL_TICKS = 10;
    /** Потолок числа точек на одну отрисовку: шаг подстраивается под размер области. */
    private static final int OUTLINE_MAX_POINTS = 240;
    /** Рамку не рисуем, если область необозримо велика (например радиус global). */
    private static final int OUTLINE_MAX_EDGE = 512;

    private int outlineTick = 0;

    private PreviewManager() {}

    /** Асинхронный предпросмотр: выборка в фоне, рассылка пакетов — на главном потоке. */
    public void start(ServerPlayer player, RollbackFilter filter, Consumer<Component> out) {
        if (!GLStorage.isReady()) { out.accept(Component.literal("§cХранилище недоступно.")); return; }
        cancel(player); // снять предыдущий preview
        MinecraftServer server = player.server;

        dbExecutor.submit(() -> {
            List<RollbackData.BlockChange> blocks;
            try (Connection conn = GLStorage.get().database().newConnection()) {
                blocks = RollbackData.queryBlocks(conn, filter, true); // preview показывает, что сделает откат
            } catch (Exception e) {
                server.execute(() -> out.accept(Component.literal("§c" + RollbackManager.translateDbError(e))));
                return;
            }
            final List<RollbackData.BlockChange> fBlocks = blocks;
            server.execute(() -> applyPreview(player, filter, fBlocks, out));
        });
    }

    /** Главный поток: вычисляем состояния, шлём пакеты, сохраняем сессию. */
    private void applyPreview(ServerPlayer player, RollbackFilter filter,
                              List<RollbackData.BlockChange> blocks, Consumer<Component> out) {
        if (blocks.isEmpty()) { out.accept(Component.literal("§7[Preview] Нет изменений по фильтру.")); return; }
        ServerLevel level = (ServerLevel) player.level();

        // Итог позиции задаёт САМАЯ СТАРАЯ запись: откат применяет обратные операции от новых
        // к старым, и последней применяется именно она. Раньше здесь бралась самая новая, из-за
        // чего превью показывало промежуточное состояние — например блок, поставленный в середине
        // окна, хотя на начало окна там было пусто.
        Map<BlockPos, BlockState> preview = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, RollbackData.BlockChange> e
                : RollbackData.finalChangePerPosition(blocks).entrySet()) {
            BlockState state = BlockRestorer.computeReverseState(level, e.getValue());
            if (state != null) preview.put(e.getKey(), state);
        }

        Map<BlockPos, BlockState> real = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : preview.entrySet()) {
            real.put(e.getKey(), level.getBlockState(e.getKey()));
            player.connection.send(new ClientboundBlockUpdatePacket(e.getKey(), e.getValue()));
        }
        sessions.put(player.getUUID(), new Session(level, real, player.blockPosition(),
                new BlockPos(filter.minX, filter.minY, filter.minZ),
                new BlockPos(filter.maxX, filter.maxY, filter.maxZ)));
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

    /**
     * Рамка области из партиклов — чтобы было видно, какая территория попадает под откат,
     * а какая нет. Мод остаётся чисто серверным: партиклы шлются пакетом конкретному игроку.
     * <p>
     * Шаг между точками подстраивается под размер области, поэтому число партиклов ограничено
     * сверху и большая область не превращается в стену частиц.
     */
    private static void drawOutline(ServerPlayer player, Session s) {
        ServerLevel level = s.level;
        int minX = s.boxMin.getX(), minY = s.boxMin.getY(), minZ = s.boxMin.getZ();
        int maxX = s.boxMax.getX(), maxY = s.boxMax.getY(), maxZ = s.boxMax.getZ();
        long lx = (long) maxX - minX + 1, ly = (long) maxY - minY + 1, lz = (long) maxZ - minZ + 1;
        // Радиус global раскрывает бокс на весь диапазон int — рисовать нечего.
        if (lx > OUTLINE_MAX_EDGE || ly > OUTLINE_MAX_EDGE || lz > OUTLINE_MAX_EDGE) return;

        double step = Math.max(1.0, 4.0 * (lx + ly + lz) / OUTLINE_MAX_POINTS);
        double x0 = minX, y0 = minY, z0 = minZ;
        double x1 = maxX + 1.0, y1 = maxY + 1.0, z1 = maxZ + 1.0;

        for (double x = x0; x <= x1; x += step) {
            spark(player, level, x, y0, z0); spark(player, level, x, y0, z1);
            spark(player, level, x, y1, z0); spark(player, level, x, y1, z1);
        }
        for (double y = y0; y <= y1; y += step) {
            spark(player, level, x0, y, z0); spark(player, level, x0, y, z1);
            spark(player, level, x1, y, z0); spark(player, level, x1, y, z1);
        }
        for (double z = z0; z <= z1; z += step) {
            spark(player, level, x0, y0, z); spark(player, level, x0, y1, z);
            spark(player, level, x1, y0, z); spark(player, level, x1, y1, z);
        }
    }

    /** Одна точка рамки. Скорость 0, смещение 0 — частица висит ровно на границе. */
    private static void spark(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        level.sendParticles(player, ParticleTypes.END_ROD, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** Вызывается каждый тик: рамка области, авто-отмена по таймауту/перемещению/смене измерения. */
    public void tick(net.minecraft.server.MinecraftServer server) {
        if (sessions.isEmpty()) return;
        boolean drawNow = (++outlineTick % OUTLINE_INTERVAL_TICKS) == 0;
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
                return;
            }
            if (drawNow) drawOutline(p, s);
        });
    }
}
