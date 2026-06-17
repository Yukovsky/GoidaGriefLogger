package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.SourceType;
import com.gle.core.SystemUsers;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SculkBlock;
import net.minecraft.world.level.block.SculkVeinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * REQ-LOG-009 (Фаза 3): экологические изменения мира (огонь, лава, вода, лёд/снег, скалк),
 * которые происходят прямым {@code setBlock} без событий. Классифицируется по типам блоков
 * из {@link com.gle.mixin.LevelChunkMixin}. Каждая категория управляется своим флагом конфига и
 * (для жидкостей/огня) ограничивается rate-limit'ом на позицию.
 */
public final class EnvironmentLogger {

    private EnvironmentLogger() {}

    private static final ConcurrentHashMap<Long, Long> RATE = new ConcurrentHashMap<>();

    /** Быстрая проверка для мест с высокой нагрузкой: включена ли хоть одна экологическая категория. */
    public static boolean anyEnabled() {
        return GLEConfig.enableFireSpread.get() || GLEConfig.enableLavaFlow.get()
                || GLEConfig.enableWaterFlow.get() || GLEConfig.enableSculk.get()
                || GLEConfig.enableIceSnow.get();
    }

    public static void consider(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!GLStorage.isReady()) return;
        if (oldState.getBlock() == newState.getBlock()) return;

        boolean newAir = newState.isAir();
        boolean oldAir = oldState.isAir();

        // --- Огонь ---
        if (GLEConfig.enableFireSpread.get()) {
            if (newState.getBlock() instanceof BaseFireBlock) {
                if (rateOk(pos)) logPlace(level, pos, newState, SourceType.FIRE, SystemUsers.FIRE);
                return;
            }
            if (oldState.getBlock() instanceof BaseFireBlock && newAir) {
                logBreak(level, pos, oldState, SourceType.FIRE, SystemUsers.FIRE);
                return;
            }
        }

        // --- Жидкости (появление) ---
        FluidState nf = newState.getFluidState();
        if (!nf.isEmpty() && (oldAir || oldState.canBeReplaced())) {
            if (nf.is(FluidTags.WATER) && GLEConfig.enableWaterFlow.get()) {
                if (rateOk(pos)) logPlace(level, pos, newState, SourceType.WATER, SystemUsers.WATER);
                return;
            }
            if (nf.is(FluidTags.LAVA) && GLEConfig.enableLavaFlow.get()) {
                if (rateOk(pos)) logPlace(level, pos, newState, SourceType.LAVA, SystemUsers.LAVA);
                return;
            }
        }
        // --- Жидкости (исчезновение) ---
        FluidState of = oldState.getFluidState();
        if (newAir && !of.isEmpty()) {
            if (of.is(FluidTags.WATER) && GLEConfig.enableWaterFlow.get()) {
                if (rateOk(pos)) logBreak(level, pos, oldState, SourceType.WATER, SystemUsers.WATER);
                return;
            }
            if (of.is(FluidTags.LAVA) && GLEConfig.enableLavaFlow.get()) {
                if (rateOk(pos)) logBreak(level, pos, oldState, SourceType.LAVA, SystemUsers.LAVA);
                return;
            }
        }

        // --- Лёд/снег: образование и таяние ---
        if (GLEConfig.enableIceSnow.get()) {
            boolean newSnow = newState.getBlock() instanceof SnowLayerBlock || newState.is(Blocks.SNOW);
            boolean newIce  = newState.getBlock() instanceof IceBlock;
            boolean oldSnow = oldState.getBlock() instanceof SnowLayerBlock || oldState.is(Blocks.SNOW);
            boolean oldIce  = oldState.getBlock() instanceof IceBlock;
            // образование (лёд из воды, снег из воздуха/заменяемого)
            if ((newSnow || newIce) && !oldSnow && !oldIce && (oldAir || oldState.canBeReplaced())) {
                logPlace(level, pos, newState, SourceType.MELTING, SystemUsers.WATER);
                return;
            }
            // таяние/исчезновение (лёд→вода, снег→воздух)
            if ((oldIce || oldSnow) && !newIce && !newSnow) {
                logBreak(level, pos, oldState, SourceType.MELTING, SystemUsers.WATER);
                return;
            }
        }

        // --- Скалк: распространение и исчезновение ---
        if (GLEConfig.enableSculk.get()) {
            boolean newSculk = newState.getBlock() instanceof SculkBlock || newState.getBlock() instanceof SculkVeinBlock;
            boolean oldSculk = oldState.getBlock() instanceof SculkBlock || oldState.getBlock() instanceof SculkVeinBlock;
            if (newSculk && !oldSculk) {
                logPlace(level, pos, newState, SourceType.SCULK, SystemUsers.SERVER);
            } else if (oldSculk && !newSculk) {
                logBreak(level, pos, oldState, SourceType.SCULK, SystemUsers.SERVER);
            }
        }
    }

    private static void logPlace(ServerLevel level, BlockPos pos, BlockState state, String src, String user) {
        BlockLogger.log(level, pos, state, GLActions.PLACE_BLOCK, src, user, null, null, false);
    }

    private static void logBreak(ServerLevel level, BlockPos pos, BlockState state, String src, String user) {
        BlockLogger.log(level, pos, state, GLActions.BREAK_BLOCK, src, user, null, null, false);
    }

    private static boolean rateOk(BlockPos pos) {
        int sec = GLEConfig.environmentalRateLimitPerBlockSec.get();
        if (sec <= 0) return true;
        long now = System.currentTimeMillis();
        long key = pos.asLong();
        Long last = RATE.get(key);
        if (last != null && now - last < sec * 1000L) return false;
        RATE.put(key, now);
        if (RATE.size() > 8192) RATE.entrySet().removeIf(e -> now - e.getValue() > sec * 4000L);
        return true;
    }
}
