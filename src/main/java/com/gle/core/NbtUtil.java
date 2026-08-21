package com.gle.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * Захват и сериализация NBT для точного восстановления состояния BlockEntity (см. §6.9 ТЗ).
 * Использует тот же pipeline, что и сохранение мира: {@code saveWithFullMetadata} /
 * {@code loadWithComponents}. NBT хранится сжатым (gzip) в BLOB.
 */
public final class NbtUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/Nbt");

    /**
     * Служебный ключ внутри снимка NBT, куда GLE кладёт SNBT блок-стейта на момент захвата.
     * Нужен, чтобы при откате слома игроком вернуть блок С ПРАВИЛЬНОЙ ориентацией
     * (GriefLogger в строке сломанного блока хранит только материал, без blockstate).
     * Перед {@code loadWithComponents} ключ удаляется, чтобы не попасть в BlockEntity.
     */
    public static final String EMBEDDED_STATE_KEY = "gle_embedded_state";

    private NbtUtil() {}

    /** Результат захвата NBT: байты (или null) и флаг превышения лимита. */
    public record Capture(byte @Nullable [] bytes, boolean truncated) {
        public static final Capture EMPTY = new Capture(null, false);
    }

    /**
     * Снять NBT BlockEntity по позиции ДО изменения блока.
     * @param maxNbtSizeKb лимит размера; 0 = не сохранять NBT вовсе.
     */
    public static Capture captureBlockEntity(LevelAccessor level, BlockPos pos,
                                             HolderLookup.Provider registries, int maxNbtSizeKb) {
        if (maxNbtSizeKb <= 0) return Capture.EMPTY;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return Capture.EMPTY;
        try {
            CompoundTag tag = be.saveWithFullMetadata(registries);
            byte[] bytes = compress(tag);
            if (bytes.length > maxNbtSizeKb * 1024) {
                return new Capture(null, true); // превышен лимит → без NBT, пометить truncated
            }
            return new Capture(bytes, false);
        } catch (Exception e) {
            LOGGER.warn("Не удалось снять NBT BlockEntity на {}: {}", pos, e.getMessage());
            return Capture.EMPTY;
        }
    }

    /**
     * Снять снимок сломанного блока с BlockEntity для точного отката: полный NBT самого
     * BlockEntity плюс blockstate ({@link #EMBEDDED_STATE_KEY}).
     * <p>
     * Решение «есть ли что сохранять» принимается по НАЛИЧИЮ BlockEntity, а НЕ по capability и
     * не по {@code instanceof Container}: это надёжно покрывает любые вместилища на capability
     * (Create Item Vault, Sophisticated Backpacks, Tom's Storage, ящики-моды), у которых
     * содержимое лежит в NBT BlockEntity, но интерфейс {@code Container} они не реализуют, а
     * регистрация capability может отдавать {@code null} в момент слома.
     * <p>
     * У блока без BlockEntity сохранять нечего: его blockstate тем же событием пишется
     * в {@code blocks.extra_data}, откуда откат его и читает. Отдельная строка в
     * {@code gle_block_nbt} была бы дубликатом на каждый сломанный забор или ступеньку.
     *
     * @param maxNbtSizeKb лимит размера NBT BlockEntity; 0 = не сохранять NBT (но blockstate всё равно сохраним).
     * @return {@link Capture#EMPTY} если сохранять нечего.
     */
    public static Capture captureBreakSnapshot(LevelAccessor level, BlockPos pos, BlockState state,
                                               HolderLookup.Provider registries, int maxNbtSizeKb) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return Capture.EMPTY;
        try {
            if (maxNbtSizeKb > 0) {
                CompoundTag tag = be.saveWithFullMetadata(registries);
                tag.putString(EMBEDDED_STATE_KEY, blockStateToSnbt(state));
                byte[] bytes = compress(tag);
                if (bytes.length <= maxNbtSizeKb * 1024) {
                    return new Capture(bytes, false);
                }
                // NBT не влез в лимит — сохраняем хотя бы blockstate, помечаем truncated.
                return new Capture(compress(stateOnlyTag(state)), true);
            }
            // NBT отключён конфигом — только blockstate.
            return new Capture(compress(stateOnlyTag(state)), false);
        } catch (Exception e) {
            LOGGER.warn("Не удалось снять снимок блока на {}: {}", pos, e.getMessage());
            return Capture.EMPTY;
        }
    }

    private static CompoundTag stateOnlyTag(BlockState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString(EMBEDDED_STATE_KEY, blockStateToSnbt(state));
        return tag;
    }

    public static byte[] compress(CompoundTag tag) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    public static CompoundTag decompress(byte[] bytes) throws Exception {
        return NbtIo.readCompressed(new java.io.ByteArrayInputStream(bytes),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
    }

    /** Сериализовать BlockState в SNBT-строку (для хранения в extra_data и последующего роллбека). */
    public static String blockStateToSnbt(BlockState state) {
        return NbtUtils.writeBlockState(state).toString();
    }
}
