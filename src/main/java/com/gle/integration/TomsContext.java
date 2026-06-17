package com.gle.integration;

import net.minecraft.server.level.ServerPlayer;

/**
 * Контекст «текущий игрок взаимодействует с терминалом Tom's Simple Storage» на потоке сервера.
 * <p>
 * Миксин в меню терминала ставит сюда игрока на время обработки ОДНОГО пакета взаимодействия
 * (клик/шифт-клик), а миксин в {@code StorageTerminalBlockEntity#pullStack/pushStack} читает его,
 * чтобы атрибутировать перемещение предметов реальному игроку (а не {@code [AUTO]}). Заодно на это
 * время подавляется универсальный перехват {@link AutomationItemLogger}, чтобы не задвоить запись.
 * <p>
 * Область строго в пределах синхронной обработки одного пакета — других игроков/автоматики в этот
 * момент на потоке нет, поэтому ThreadLocal не «протекает» между сессиями.
 */
public final class TomsContext {

    private static final ThreadLocal<ServerPlayer> PLAYER = new ThreadLocal<>();

    private TomsContext() {}

    public static void set(ServerPlayer player) {
        PLAYER.set(player);
    }

    public static void clear() {
        PLAYER.remove();
    }

    public static ServerPlayer current() {
        return PLAYER.get();
    }

    public static boolean isActive() {
        return PLAYER.get() != null;
    }
}
