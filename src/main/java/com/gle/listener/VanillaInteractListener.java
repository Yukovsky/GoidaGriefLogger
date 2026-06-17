package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.core.VanillaInteractables;
import com.gle.db.BlockLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * Право-клик игрока по интерактивным ВАНИЛЬНЫМ блокам (двери/люки/калитки, кнопки/рычаги,
 * контейнеры, верстаки, эндер-сундук и т.д.) → запись взаимодействия в {@code blocks} (INTERACT=2).
 * <p>
 * Дословный порт write-пути {@code RightClickBlockEvent} GriefLogger (Apache-2.0): набор блоков
 * берётся из {@link VanillaInteractables}. После поглощения GL (Путь E) это пишет единый writer.
 * Модовые хранилища/терминалы (не {@code minecraft}) логирует {@link ContainerAccessListener}.
 */
public final class VanillaInteractListener {

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!GLStorage.isReady()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer) || player instanceof FakePlayer) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!VanillaInteractables.isInteractable(state.getBlock())) return;

        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String dimension = level.dimension().location().toString();
        if (contains(GLEConfig.worldBlacklist.get(), dimension)) return;
        String material = GLMaterials.normalize(key);
        if (contains(GLEConfig.blockBlacklist.get(), material)) return;

        GLStorage.get().blocks().insert(new BlockLogDao.BlockEntry(
                System.currentTimeMillis(),
                player.getUUID().toString(),
                dimension,
                pos.getX(), pos.getY(), pos.getZ(),
                material,
                GLActions.INTERACT_BLOCK,
                null,   // source_type: действие реального игрока (как у GriefLogger)
                null, null, null, false));
    }

    private static boolean contains(List<? extends String> list, String value) {
        return list != null && list.contains(value);
    }
}
