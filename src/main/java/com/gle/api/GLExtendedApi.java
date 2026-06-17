package com.gle.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Публичный API GLE для других модов (см. §9 ТЗ). Доступ через {@link GLExtended#getApi()}.
 * Все методы потокобезопасны и могут вызываться как с игрового, так и с асинхронного потока.
 */
public interface GLExtendedApi {

    /** Логировать разрушение блока не-игровым источником. */
    void logBlockBreak(ServerLevel level, BlockPos pos, BlockState brokenState,
                       @Nullable CompoundTag nbt, @Nullable UUID causingPlayer, String sourceType);

    /** Логировать установку блока не-игровым источником. */
    void logBlockPlace(ServerLevel level, BlockPos pos, BlockState placedState,
                       @Nullable CompoundTag nbt, @Nullable UUID causingPlayer, String sourceType);

    /** Зарегистрировать обработчик роллбека для кастомного источника. */
    void registerRollbackHandler(ResourceLocation sourceType, RollbackHandler handler);

    /** Проверить, доступна ли БД для записи. */
    boolean isDatabaseAvailable();

    @FunctionalInterface
    interface RollbackHandler {
        /**
         * @param restore true, если это обратная операция (/gl restore)
         * @return true при успехе
         */
        boolean rollback(ServerLevel level, BlockPos pos, @Nullable CompoundTag data, boolean restore);
    }
}
