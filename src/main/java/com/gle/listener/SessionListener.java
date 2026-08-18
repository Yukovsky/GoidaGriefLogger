package com.gle.listener;

import com.gle.core.GLActions;
import com.gle.core.db.GLStorage;
import com.gle.core.db.SessionDao;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Сессии игроков (вход/выход) — игровое событие, которое раньше писал сам GriefLogger.
 * После поглощения GL (Путь E) его пишет ЕДИНЫЙ писатель GoidaGriefLogger через {@link SessionDao}.
 */
public final class SessionListener {

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            record(player, GLActions.SESSION_JOIN);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            record(player, GLActions.SESSION_QUIT);
            // Иначе набор рос бы вечно, а режим инспектора неожиданно оказывался включён
            // при следующем входе.
            com.gle.core.command.InspectManager.clear(player.getUUID());
        }
    }

    private static void record(ServerPlayer player, int action) {
        if (!GLStorage.isReady()) return;
        BlockPos pos = player.blockPosition();
        SessionDao.SessionEntry entry = new SessionDao.SessionEntry(
                System.currentTimeMillis(),
                player.getGameProfile().getName(),
                player.getUUID().toString(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                action
        );
        GLStorage.get().sessions().insert(entry);
    }
}
