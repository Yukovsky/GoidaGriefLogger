package com.gle.api;

import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.SystemUsers;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реализация публичного API GLE. Делегирует записи в {@link BlockLogger}.
 */
public final class GLExtendedApiImpl implements GLExtendedApi {

    private final Map<ResourceLocation, RollbackHandler> rollbackHandlers = new ConcurrentHashMap<>();

    @Override
    public void logBlockBreak(ServerLevel level, BlockPos pos, BlockState brokenState,
                              @Nullable CompoundTag nbt, @Nullable UUID causingPlayer, String sourceType) {
        BlockLogger.log(level, pos, brokenState, GLActions.BREAK_BLOCK,
                sourceType, systemUserFor(sourceType), causingPlayer, null, true);
    }

    @Override
    public void logBlockPlace(ServerLevel level, BlockPos pos, BlockState placedState,
                              @Nullable CompoundTag nbt, @Nullable UUID causingPlayer, String sourceType) {
        BlockLogger.log(level, pos, placedState, GLActions.PLACE_BLOCK,
                sourceType, systemUserFor(sourceType), causingPlayer, null, false);
    }

    @Override
    public void registerRollbackHandler(ResourceLocation sourceType, RollbackHandler handler) {
        rollbackHandlers.put(sourceType, handler);
    }

    public Map<ResourceLocation, RollbackHandler> rollbackHandlers() {
        return rollbackHandlers;
    }

    @Override
    public boolean isDatabaseAvailable() {
        return GLStorage.isReady();
    }

    /** Подбор системного пользователя по префиксу source_type. */
    private static String systemUserFor(String sourceType) {
        if (sourceType == null) return SystemUsers.SERVER;
        if (sourceType.startsWith("create")) return SystemUsers.CREATE;
        if (sourceType.startsWith("entity")) return SystemUsers.MOB;
        return switch (sourceType) {
            case "tnt" -> SystemUsers.TNT;
            case "creeper" -> SystemUsers.CREEPER;
            case "piston", "piston_destroy" -> SystemUsers.PISTON;
            case "hopper" -> SystemUsers.HOPPER;
            case "gravity" -> SystemUsers.GRAVITY;
            case "fire" -> SystemUsers.FIRE;
            case "lava" -> SystemUsers.LAVA;
            case "water" -> SystemUsers.WATER;
            default -> SystemUsers.SERVER;
        };
    }
}
