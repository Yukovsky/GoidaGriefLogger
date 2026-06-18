package com.gle.listener;

import com.gle.GLEConfig;
import com.gle.core.BlockLogger;
import com.gle.core.GLActions;
import com.gle.core.NbtUtil;
import com.gle.core.db.GLStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Слом и установка блоков НАСТОЯЩИМ игроком — игровое событие, которое раньше писал GriefLogger.
 * После поглощения GL (Путь E) его пишет ЕДИНЫЙ писатель GoidaGriefLogger: строка в {@code blocks}
 * с действием BREAK/PLACE от имени UUID игрока и {@code source_type = NULL} (как у GL для игроков).
 * <p>
 * Дополнительно при сломе снимаем NBT-снимок ({@code gle_block_nbt}) — это улучшение GLE поверх GL,
 * чтобы откат вернул контейнер С содержимым и блок с правильной ориентацией.
 * Не-игроки (моды/мобы/fake-players) обрабатываются {@link ModBlockListener} — здесь только игроки.
 */
public final class PlayerBlockListener {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!isRealPlayer(event.getEntity())) return;
        ServerPlayer player = (ServerPlayer) event.getEntity();
        BlockState placed = event.getPlacedBlock();
        BlockLogger.logAs(level, event.getPos(), placed, GLActions.PLACE_BLOCK,
                null, player.getUUID().toString(), null, null, false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getPlayer();
        if (!isRealPlayer(player)) return;

        // Снимок содержимого/ориентации сломанного блока для точного отката (gle_block_nbt).
        captureBreakSnapshot(level, event.getPos());

        BlockState state = event.getState();
        BlockLogger.logAs(level, event.getPos(), state, GLActions.BREAK_BLOCK,
                null, ((ServerPlayer) player).getUUID().toString(), null, null, false);
    }

    private static boolean isRealPlayer(Object entity) {
        return entity instanceof ServerPlayer && !(entity instanceof FakePlayer);
    }

    private static void captureBreakSnapshot(ServerLevel level, BlockPos pos) {
        if (!GLStorage.isReady()) return;
        NbtUtil.Capture cap = NbtUtil.captureBreakSnapshot(level, pos, level.getBlockState(pos),
                level.registryAccess(), GLEConfig.maxNbtSizeKb.get());
        if (cap.bytes() != null) {
            GLStorage.get().events().insertBlockNbt(System.currentTimeMillis(),
                    level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), cap.bytes());
        }
    }
}
