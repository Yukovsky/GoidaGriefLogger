package com.gle.rollback;

import com.gle.core.GLActions;
import com.gle.core.NbtUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Применение операции к блоку (откат/восстановление) и восстановление состояния через NBT (§6.9).
 * <p>
 * Перед заменой блока контейнер очищается, чтобы Minecraft НЕ выбрасывал его содержимое
 * (иначе при откате/restore из всех контейнеров сыпались бы предметы).
 */
public final class BlockRestorer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/Restore");

    private BlockRestorer() {}

    /**
     * Применить изменение блока.
     * @param reverse true = откат (PLACE→air, BREAK→вернуть блок); false = restore (PLACE→поставить, BREAK→air)
     * @return true если что-то применено.
     */
    public static boolean apply(ServerLevel level, RollbackData.BlockChange change, boolean reverse) {
        BlockPos pos = new BlockPos(change.x(), change.y(), change.z());

        // Восстанавливаем содержимое BlockEntity только когда возвращаем сломанный блок (откат BREAK).
        boolean restoringBrokenBlock = reverse && change.action() == GLActions.BREAK_BLOCK;

        // Снимок NBT декомпрессим один раз: из него же берём встроенный blockstate (ориентацию),
        // если GriefLogger в строке слома сохранил только материал (слом контейнера игроком).
        CompoundTag beTag = null;
        BlockState embeddedState = null;
        if (restoringBrokenBlock && change.nbt() != null) {
            try {
                beTag = NbtUtil.decompress(change.nbt());
                embeddedState = readEmbeddedState(level, beTag);
            } catch (Exception e) {
                LOGGER.debug("Не удалось прочитать снимок NBT на {}: {}", pos, e.getMessage());
            }
        }

        BlockState target = embeddedState != null ? embeddedState
                : (reverse ? computeReverseState(level, change) : computeForwardState(level, change));
        if (target == null) return false;

        BlockState previous = level.getBlockState(pos);
        clearContainer(level, pos); // не даём выпасть предметам из заменяемого контейнера

        // Двойные блоки (двери, кровати, высокие растения): GriefLogger часто пишет только одну
        // половину, и при обычном setBlock вторая отваливается. Ставим обе половины без соседских
        // апдейтов (флаг 2), чтобы они не «самоуничтожились» из-за проверки опоры.
        if (!placeDoubleBlock(level, pos, target)) {
            level.setBlock(pos, target, Block.UPDATE_ALL);
        }

        if (restoringBrokenBlock && beTag != null) {
            restoreBlockEntity(level, pos, beTag);
        }
        level.sendBlockUpdated(pos, previous, target, Block.UPDATE_CLIENTS);
        return true;
    }

    /**
     * Если {@code target} — двойной блок (DoubleBlockHalf или кровать), поставить обе половины
     * с корректными позициями/состояниями. Возвращает true, если обработал блок как двойной.
     */
    private static boolean placeDoubleBlock(ServerLevel level, BlockPos pos, BlockState target) {
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            BlockPos lowerPos = half == DoubleBlockHalf.LOWER ? pos : pos.below();
            level.setBlock(lowerPos, target.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER),
                    Block.UPDATE_CLIENTS);
            level.setBlock(lowerPos.above(), target.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                    Block.UPDATE_CLIENTS);
            return true;
        }
        if (target.hasProperty(BlockStateProperties.BED_PART) && target.hasProperty(BedBlock.FACING)) {
            BedPart part = target.getValue(BlockStateProperties.BED_PART);
            Direction facing = target.getValue(BedBlock.FACING); // указывает от ножной части к изголовью
            BlockPos footPos = part == BedPart.FOOT ? pos : pos.relative(facing.getOpposite());
            level.setBlock(footPos, target.setValue(BlockStateProperties.BED_PART, BedPart.FOOT), Block.UPDATE_CLIENTS);
            level.setBlock(footPos.relative(facing), target.setValue(BlockStateProperties.BED_PART, BedPart.HEAD),
                    Block.UPDATE_CLIENTS);
            return true;
        }
        return false;
    }

    /** Прочитать встроенный в снимок blockstate ({@link NbtUtil#EMBEDDED_STATE_KEY}); null если его нет. */
    @Nullable
    private static BlockState readEmbeddedState(ServerLevel level, CompoundTag beTag) {
        if (!beTag.contains(NbtUtil.EMBEDDED_STATE_KEY)) return null;
        try {
            CompoundTag stateTag = TagParser.parseTag(beTag.getString(NbtUtil.EMBEDDED_STATE_KEY));
            HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
            return net.minecraft.nbt.NbtUtils.readBlockState(blocks, stateTag);
        } catch (Exception e) {
            LOGGER.debug("Не удалось разобрать встроенный blockstate: {}", e.getMessage());
            return null;
        }
    }

    /** Целевое состояние ОТКАТА без применения (для preview). */
    @Nullable
    public static BlockState computeReverseState(ServerLevel level, RollbackData.BlockChange change) {
        if (change.action() == GLActions.PLACE_BLOCK) return Blocks.AIR.defaultBlockState();
        return resolveState(level, change); // BREAK → вернуть сломанный блок
    }

    /** Целевое состояние RESTORE (повтор действия) без применения. */
    @Nullable
    public static BlockState computeForwardState(ServerLevel level, RollbackData.BlockChange change) {
        if (change.action() == GLActions.PLACE_BLOCK) return resolveState(level, change); // повторно поставить
        return Blocks.AIR.defaultBlockState(); // BREAK → убрать
    }

    /** Очистить содержимое контейнера на позиции, чтобы при замене блока ничего не выпало. */
    public static void clearContainer(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container c) {
            try { c.clearContent(); } catch (Exception ignored) {}
        }
    }

    @Nullable
    private static BlockState resolveState(ServerLevel level, RollbackData.BlockChange change) {
        // 1. Точное состояние из extra_data ("state" в SNBT) — записывается GLE.
        if (change.extraData() != null && change.extraData().contains("\"state\"")) {
            try {
                JsonObject json = JsonParser.parseString(change.extraData()).getAsJsonObject();
                if (json.has("state")) {
                    CompoundTag tag = TagParser.parseTag(json.get("state").getAsString());
                    HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
                    return net.minecraft.nbt.NbtUtils.readBlockState(blocks, tag);
                }
            } catch (Exception e) {
                LOGGER.debug("Не удалось разобрать state из extra_data: {}", e.getMessage());
            }
        }
        Block block = blockFromMaterial(change.material());
        return block == null ? null : block.defaultBlockState();
    }

    @Nullable
    private static Block blockFromMaterial(@Nullable String material) {
        if (material == null || material.isBlank()) return null;
        ResourceLocation id = material.contains(":")
                ? ResourceLocation.parse(material)
                : ResourceLocation.fromNamespaceAndPath("minecraft", material);
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    private static void restoreBlockEntity(ServerLevel level, BlockPos pos, CompoundTag tag) {
        try {
            tag.remove(NbtUtil.EMBEDDED_STATE_KEY); // служебный ключ GLE — не для BlockEntity
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
            }
        } catch (Exception e) {
            LOGGER.warn("Не удалось восстановить BlockEntity на {}: {}", pos, e.getMessage());
        }
    }
}
