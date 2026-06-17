package com.gle.core;

import com.gle.GLEConfig;
import com.gle.db.BlockLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Высокоуровневый помощник записи изменений блоков: проверки конфига/чёрных списков,
 * захват NBT, нормализация и постановка в очередь. Вызывается из листенеров на игровом потоке —
 * сам захват данных синхронный (быстрый), запись в БД асинхронная.
 */
public final class BlockLogger {

    private BlockLogger() {}

    /**
     * Залогировать изменение блока не-игровым источником.
     *
     * @param action      {@link GLActions#BREAK_BLOCK} или {@link GLActions#PLACE_BLOCK}
     * @param sourceType  значение для колонки source_type
     * @param systemUser  имя системного пользователя ({@link SystemUsers})
     * @param sourcePlayer UUID реального игрока-инициатора (или null)
     * @param captureNbt  снять ли NBT BlockEntity (актуально для BREAK перед разрушением)
     */
    public static void log(ServerLevel level, BlockPos pos, BlockState state,
                           int action, String sourceType, String systemUser,
                           @Nullable UUID sourcePlayer, @Nullable String extraData,
                           boolean captureNbt) {
        logAs(level, pos, state, action, sourceType, SystemUsers.uuidOf(systemUser),
                sourcePlayer, extraData, captureNbt);
    }

    /**
     * Как {@link #log}, но пишет от имени КОНКРЕТНОГО пользователя по его UUID (а не системного).
     * Нужно для действий, инициированных реальным игроком, но не покрытых GriefLogger
     * (например, нажатие на плиту / тропвайр) — чтобы в lookup отображалось имя игрока.
     * UUID игрока уже есть в таблице {@code users} (его завёл GriefLogger); для мобов передаётся
     * UUID системного пользователя [MOB].
     */
    public static void logAs(ServerLevel level, BlockPos pos, BlockState state,
                             int action, String sourceType, String userUuid,
                             @Nullable UUID sourcePlayer, @Nullable String extraData,
                             boolean captureNbt) {
        if (!GLStorage.isReady()) return;

        String dimension = level.dimension().location().toString();
        ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String material = GLMaterials.normalize(blockKey);

        if (isBlacklisted(dimension, sourceType, blockKey, material)) return;

        NbtUtil.Capture nbt = NbtUtil.Capture.EMPTY;
        if (captureNbt) {
            nbt = NbtUtil.captureBlockEntity(level, pos, level.registryAccess(),
                    GLEConfig.maxNbtSizeKb.get());
        }

        // SNBT блок-стейта кладём в extra_data для точного роллбека (если не передан иной extra_data).
        String extra = extraData != null ? extraData
                : "{\"state\":\"" + escape(NbtUtil.blockStateToSnbt(state)) + "\"}";

        BlockLogDao.BlockEntry entry = new BlockLogDao.BlockEntry(
                System.currentTimeMillis(),
                userUuid,
                dimension,
                pos.getX(), pos.getY(), pos.getZ(),
                material,
                action,
                sourceType,
                sourcePlayer == null ? null : sourcePlayer.toString(),
                extra,
                nbt.bytes(),
                nbt.truncated()
        );
        GLStorage.get().blocks().insert(entry);
    }

    private static boolean isBlacklisted(String dimension, String sourceType,
                                         @Nullable ResourceLocation blockKey, String material) {
        if (contains(GLEConfig.worldBlacklist.get(), dimension)) return true;
        if (sourceType != null && contains(GLEConfig.sourceTypeBlacklist.get(), sourceType)) return true;
        if (blockKey != null) {
            if (contains(GLEConfig.blockBlacklist.get(), blockKey.toString())) return true;
            if (contains(GLEConfig.blockBlacklist.get(), material)) return true;
            if (contains(GLEConfig.modBlacklist.get(), blockKey.getNamespace())) return true;
        }
        return false;
    }

    private static boolean contains(List<? extends String> list, String value) {
        return list != null && list.contains(value);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
