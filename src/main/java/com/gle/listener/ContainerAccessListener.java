package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.GLActions;
import com.gle.core.GLMaterials;
import com.gle.db.BlockLogDao;
import com.gle.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Логирование клика (открытия) по МОДОВЫМ хранилищам и терминалам — Tom's Simple Storage,
 * Sophisticated, Create-вместилища, ящики-моды и т.п. (action INTERACT=2 в таблице {@code blocks}).
 * <p>
 * Ванильные интерактивные блоки (включая контейнеры) логирует {@link VanillaInteractListener}
 * по точному набору GriefLogger. Здесь — только не-{@code minecraft} блоки, у которых есть предметный
 * capability или меню (MenuProvider), чтобы покрыть модовые хранилища, которых нет в наборе GL.
 */
public final class ContainerAccessListener {

    /** Анти-дребезг: подавляем повторные клики одного игрока по той же позиции в коротком окне. */
    private static final Map<UUID, long[]> LAST = new ConcurrentHashMap<>(); // uuid -> [posLong, timeMs]

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!GLEConfig.enableContainerAccess.get()) return;
        if (!GLStorage.isReady()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer) || player instanceof FakePlayer) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        // Ванильные интерактивные блоки логирует VanillaInteractListener (точный набор GL);
        // здесь — только МОДОВЫЕ хранилища/терминалы (не minecraft), которых нет в наборе GL.
        if (key == null || "minecraft".equals(key.getNamespace())) return;
        if (!isStorageAccess(level, pos, state)) return;

        String dimension = level.dimension().location().toString();
        if (contains(GLEConfig.worldBlacklist.get(), dimension)) return;
        String material = GLMaterials.normalize(key);
        if (contains(GLEConfig.blockBlacklist.get(), material)) return;
        if (contains(GLEConfig.modBlacklist.get(), key.getNamespace())) return;

        long now = System.currentTimeMillis();
        if (isDuplicate(player.getUUID(), pos, now)) return;

        GLStorage.get().blocks().insert(new BlockLogDao.BlockEntry(
                now,
                player.getUUID().toString(),
                dimension,
                pos.getX(), pos.getY(), pos.getZ(),
                material,
                GLActions.INTERACT_BLOCK,
                "access",
                null, null, null, false));
    }

    /** Блок открывает хранилище/меню: есть предметный capability или его BlockEntity — MenuProvider. */
    private static boolean isStorageAccess(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) return true;
        } catch (Exception ignored) {}
        if (state.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof MenuProvider;
        }
        return false;
    }

    private static boolean isDuplicate(UUID uuid, BlockPos pos, long now) {
        int window = Math.max(GLEConfig.deduplicationWindowMs.get(), 250);
        long posLong = pos.asLong();
        long[] prev = LAST.put(uuid, new long[]{posLong, now});
        return prev != null && prev[0] == posLong && (now - prev[1]) < window;
    }

    private static boolean contains(List<? extends String> list, String value) {
        return list != null && list.contains(value);
    }
}
