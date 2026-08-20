package com.gle.listener;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто «поджёг» взрыв, у которого нет сущности-источника.
 * <p>
 * Кровать в Нижнем мире и якорь возрождения взрываются от использования БЛОКА: у такого взрыва
 * {@code getDirectSourceEntity} и {@code getIndirectSourceEntity} пусты, и виновника взять
 * неоткуда — а это классический способ снести чужую базу. Здесь запоминается последнее
 * использование такого блока, чтобы взрыв в том же месте связать с игроком.
 * <p>
 * Окно намеренно короткое: взрыв происходит в том же тике, что и использование. Совпадение
 * требуется и по времени, и по месту — «ближайший игрок» как догадка не используется.
 */
public final class BlastPrimingTracker {

    private record Priming(BlockPos pos, long time) {}

    private static final Map<UUID, Priming> RECENT = new ConcurrentHashMap<>();

    /** Взрыв идёт в том же тике; секунда — запас на лаг, не более. */
    private static final long WINDOW_MS = 1000;
    /** Кровать взрывается не строго в клике: рядом стоящая половина, отскок игрока. */
    private static final double RANGE_SQR = 4.0 * 4.0;

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!(state.getBlock() instanceof BedBlock) && !(state.getBlock() instanceof RespawnAnchorBlock)) return;
        RECENT.put(sp.getUUID(), new Priming(event.getPos().immutable(), System.currentTimeMillis()));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RECENT.remove(event.getEntity().getUUID());
    }

    /**
     * uuid игрока, только что использовавшего кровать или якорь рядом с этой позицией.
     *
     * @return {@code null}, если совпадения нет — тогда взрыв остаётся системным, без обвинения
     */
    @Nullable
    public static UUID resolve(BlockPos explosionAt) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Priming> e : RECENT.entrySet()) {
            Priming p = e.getValue();
            if (now - p.time() > WINDOW_MS) { RECENT.remove(e.getKey()); continue; }
            if (explosionAt.distSqr(p.pos()) <= RANGE_SQR) return e.getKey();
        }
        return null;
    }

    /** Использовали ли рядом кровать или якорь — для уточнения source_type. */
    public static boolean primedNear(BlockPos explosionAt) {
        return resolve(explosionAt) != null;
    }
}
