package com.gle.listener;

import com.gle.core.command.InspectManager;
import com.gle.core.command.LookupService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Режим инспектора ({@code /gl inspect}, как в CoreProtect): пока режим включён у игрока, его клик по
 * блоку (лево/право) НЕ ломает/не открывает блок, а показывает историю этого места ({@link LookupService}).
 * Состояние режима держит {@link InspectManager}.
 */
public final class InspectListener {

    @SubscribeEvent
    public void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        handle(event);
    }

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        handle(event);
    }

    private static void handle(PlayerInteractEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        if (!InspectManager.isActive(player.getUUID())) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Гасим взаимодействие, чтобы инспектор не ломал/не открывал блок, и показываем историю места.
        // Конкретные подсобытия (Left/RightClickBlock) реализуют ICancellableEvent; база — нет.
        if (event instanceof net.neoforged.bus.api.ICancellableEvent cancellable) cancellable.setCanceled(true);
        BlockPos pos = event.getPos();
        LookupService.runAt(level.getServer(), player, level, pos);
    }
}
