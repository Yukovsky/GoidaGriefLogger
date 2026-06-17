package com.gle.integration;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.SystemUsers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Логирование изменений блоков, выполненных Create напрямую через {@code level.setBlock}
 * (контрапции, схематическая пушка) — обычные события NeoForge для них не стреляют.
 * Вызывается из {@code LevelChunkMixin}, когда активен {@link CreateContext}.
 */
public final class CreateLogger {

    private CreateLogger() {}

    public static void consider(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState, String source) {
        if (!GLEConfig.enableCreateIntegration.get()) return;
        if (oldState.getBlock() == newState.getBlock()) return;

        if (!newState.isAir()) {
            // блок появился/заменён — PLACE
            BlockLogger.log(level, pos, newState, GLActions.PLACE_BLOCK,
                    source, SystemUsers.CREATE, null, null, false);
        } else if (!oldState.isAir()) {
            // блок убран — BREAK (снимаем NBT прежнего блока для точного восстановления)
            BlockLogger.log(level, pos, oldState, GLActions.BREAK_BLOCK,
                    source, SystemUsers.CREATE, null, null, true);
        }
    }
}
