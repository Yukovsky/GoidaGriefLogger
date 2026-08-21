package com.gle.core;

import com.gle.core.db.BlockLogDao;
import com.gle.core.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Высокоуровневый помощник записи изменений блоков: проверки конфига/чёрных списков,
 * захват NBT, нормализация и постановка в очередь. Вызывается из листенеров на игровом потоке —
 * сам захват данных синхронный (быстрый), запись в БД асинхронная.
 */
public final class BlockLogger {

    /**
     * Готовая строка {@code extra_data} на BlockState. При взрыве (до {@code maxExplosionBlocks}
     * блоков за одно событие) она считалась заново для каждого блока, хотя стейтов там единицы —
     * камень, земля, одно и то же по тысяче раз.
     * <p>
     * Ключ безопасен и дёшев: {@link BlockState} — интернированный объект реестра, все его
     * экземпляры создаются один раз при регистрации блока, {@code equals}/{@code hashCode} остаются
     * identity. Рост кэша ограничен числом стейтов в реестре модпака, поэтому вытеснение не нужно.
     * Карта конкурентная: писать блоки может и внешний код через API, не только игровой поток.
     */
    private static final Map<BlockState, String> STATE_EXTRA_CACHE = new ConcurrentHashMap<>();

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
                    CoreConfig.get().maxNbtSizeKb());
        }

        // SNBT блок-стейта кладём в extra_data для точного роллбека (если не передан иной extra_data).
        String extra = extraData != null ? extraData : stateExtra(state);

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

    /** {@code extra_data} со SNBT блок-стейта; считается один раз на стейт (см. {@link #STATE_EXTRA_CACHE}). */
    private static String stateExtra(BlockState state) {
        return STATE_EXTRA_CACHE.computeIfAbsent(state,
                s -> "{\"state\":\"" + escape(NbtUtil.blockStateToSnbt(s)) + "\"}");
    }

    /**
     * Пройдёт ли изменение этого блока чёрные списки — та же проверка, что делает {@link #logAs}.
     * Нужна вызывающим, которые собирают сопутствующие данные ДО записи строки в {@code blocks}
     * (снимок NBT сломанного блока), чтобы не оставлять их сиротами.
     */
    public static boolean isBlacklisted(ServerLevel level, BlockState state, @Nullable String sourceType) {
        ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return isBlacklisted(level.dimension().location().toString(), sourceType,
                blockKey, GLMaterials.normalize(blockKey));
    }

    private static boolean isBlacklisted(String dimension, String sourceType,
                                         @Nullable ResourceLocation blockKey, String material) {
        CoreConfig cfg = CoreConfig.get();
        if (contains(cfg.worldBlacklist(), dimension)) return true;
        if (sourceType != null && contains(cfg.sourceTypeBlacklist(), sourceType)) return true;
        if (blockKey != null) {
            if (contains(cfg.blockBlacklist(), blockKey.toString())) return true;
            if (contains(cfg.blockBlacklist(), material)) return true;
            if (contains(cfg.modBlacklist(), blockKey.getNamespace())) return true;
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
