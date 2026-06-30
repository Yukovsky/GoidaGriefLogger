package com.gle.core.command;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Режим инспектора ({@code /gl inspect}, как в CoreProtect): пока режим включён, клик игрока по
 * блоку показывает историю этого места вместо обычного взаимодействия. Состояние — простой набор
 * UUID игроков с активным режимом ({@link com.gle.listener.InspectListener} читает его на каждый клик).
 */
public final class InspectManager {

    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();

    private InspectManager() {}

    /** Переключить режим; возвращает новое состояние ({@code true} — включён). */
    public static boolean toggle(UUID player) {
        if (ACTIVE.remove(player)) return false;
        ACTIVE.add(player);
        return true;
    }

    public static boolean isActive(UUID player) {
        return ACTIVE.contains(player);
    }

    public static void clear(UUID player) {
        ACTIVE.remove(player);
    }
}
