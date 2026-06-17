package com.gle.mixin;

import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.GLESourceResolver;
import com.gle.integration.CreateContext;
import com.gle.integration.CreateLogger;
import com.gle.integration.GriefContext;
import com.gle.listener.EnvironmentLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * REQ-LOG-009 (Фаза 3): перехват прямой установки блоков для экологических изменений
 * (огонь/лава/вода/лёд-снег/скалк), которые не порождают NeoForge-событий.
 * <p>
 * Горячий путь: при HEAD мгновенно выходим, если ни одна экологическая категория не включена
 * ({@link EnvironmentLogger#anyEnabled()}). Иначе снимаем старое состояние и передаём в логгер.
 * {@code require=0} — при несовпадении маппинга сервер не падает, фича просто неактивна.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), require = 0
    )
    private void gle$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                     CallbackInfoReturnable<BlockState> cir) {
        boolean create = CreateContext.isActive();
        GriefContext.Attribution grief = GriefContext.current();
        Entity mob = grief == null ? GriefContext.entity() : null;
        if (!create && grief == null && mob == null && !EnvironmentLogger.anyEnabled()) return;
        LevelChunk self = (LevelChunk) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) return;
        BlockState old = self.getBlockState(pos);
        if (old.getBlock() == state.getBlock()) return;
        if (create) {
            CreateLogger.consider(level, pos.immutable(), old, state, CreateContext.current());
        } else if (grief != null) {
            gle$logGrief(level, pos.immutable(), old, state, grief.sourceType(), grief.systemUser());
        } else if (mob != null) {
            GLESourceResolver.Resolved src = GLESourceResolver.resolve(mob);
            gle$logGrief(level, pos.immutable(), old, state, src.sourceType(), src.systemUser());
        } else {
            EnvironmentLogger.consider(level, pos.immutable(), old, state);
        }
    }

    /** Изменение блока в активном не-игровом контексте (гравитация, гриферство мобов и т.п.). */
    private static void gle$logGrief(ServerLevel level, BlockPos pos, BlockState old, BlockState state,
                                     String sourceType, String systemUser) {
        boolean place = !state.isAir();
        int action = place ? GLActions.PLACE_BLOCK : GLActions.BREAK_BLOCK;
        BlockState logged = place ? state : old;
        // Для слома захватываем NBT (контейнеры/ориентация), для установки — нет.
        BlockLogger.log(level, pos, logged, action, sourceType, systemUser, null, null, !place);
    }
}
