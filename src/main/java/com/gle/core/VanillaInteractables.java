package com.gle.core;

import net.minecraft.world.level.block.*;

/**
 * Набор ванильных блоков, право-клик по которым GriefLogger писал как взаимодействие (INTERACT=2):
 * двери/люки/калитки, кнопки/рычаги, контейнеры (сундуки/бочки/печи/шалкеры/воронки/раздатчики/
 * выбрасыватели/варочные стойки/крафтеры/сейфы), верстаки и прочие интерактивные блоки.
 * <p>
 * Дословный порт {@code BlockHandler.isBlockIntractable} из GriefLogger (Apache-2.0). Сохранён
 * именно этот список (а не эвристика «есть меню/хендлер»), чтобы поведение точно совпадало с GL.
 */
public final class VanillaInteractables {

    private VanillaInteractables() {}

    public static boolean isInteractable(Block block) {
        return block instanceof FenceGateBlock
                || block instanceof DispenserBlock        // покрывает и DropperBlock (наследник)
                || block instanceof NoteBlock
                || block instanceof AbstractChestBlock     // сундук, ловушка-сундук, ЭНДЕР-сундук
                || block instanceof AbstractFurnaceBlock
                || block instanceof LeverBlock
                || block instanceof TrapDoorBlock
                || block instanceof DoorBlock
                || block instanceof BrewingStandBlock
                || block instanceof DiodeBlock             // репитеры и компараторы
                || block instanceof HopperBlock
                || block instanceof DropperBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof BarrelBlock
                || block instanceof GrindstoneBlock
                || block instanceof ButtonBlock
                || block instanceof LoomBlock
                || block instanceof CraftingTableBlock
                || block instanceof CartographyTableBlock
                || block instanceof EnchantingTableBlock
                || block instanceof SmithingTableBlock
                || block instanceof StonecutterBlock
                || block instanceof CrafterBlock
                || block instanceof VaultBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof SignBlock
                || block instanceof LecternBlock
                || block instanceof BeaconBlock;
    }
}
