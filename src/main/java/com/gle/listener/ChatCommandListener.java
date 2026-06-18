package com.gle.listener;

import com.gle.core.db.GLStorage;
import com.gle.core.db.TextLogDao;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Чат и команды игроков — текстовые логи, которые раньше писал GriefLogger (таблицы {@code chats}
 * и {@code commands}). После поглощения GL (Путь E) их пишет единый writer GoidaGriefLogger.
 */
public final class ChatCommandListener {

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (!GLStorage.isReady()) return;
        ServerPlayer player = event.getPlayer();
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        GLStorage.get().text().insertChat(entry(player, level, event.getRawText()));
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!GLStorage.isReady()) return;
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        String command = event.getParseResults().getReader().getString();
        GLStorage.get().text().insertCommand(entry(player, level, command));
    }

    private static TextLogDao.TextEntry entry(ServerPlayer player, ServerLevel level, String message) {
        BlockPos pos = player.blockPosition();
        return new TextLogDao.TextEntry(
                System.currentTimeMillis(),
                player.getUUID().toString(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                message);
    }
}
