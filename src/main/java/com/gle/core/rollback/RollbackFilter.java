package com.gle.core.rollback;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Параметры выборки для роллбека/preview. Координаты заданы как ограничивающий бокс
 * (вычисляется из центра и радиуса при разборе команды).
 */
public final class RollbackFilter {

    public long timeFrom;          // unix ms (включительно)
    public long timeTo;            // unix ms (включительно), обычно «сейчас»
    public String levelName;       // dimension ResourceLocation (с minecraft:)

    @Nullable public UUID playerUuid;
    /** Включить только этих игроков ({@code u:Имя}, можно несколько); пусто = все. */
    public final java.util.List<String> playerNames = new java.util.ArrayList<>();
    /** Исключить этих игроков ({@code u:!Имя}, можно несколько); пусто = никого не исключать. */
    public final java.util.List<String> excludePlayerNames = new java.util.ArrayList<>();

    public int minX, minY, minZ;
    public int maxX, maxY, maxZ;
    public double radius;          // для записи в job
    public int centerX, centerY, centerZ;

    public boolean includeBlocks = true;
    public boolean includeItems = true;
    /**
     * Включать события, привязанные к позиции ИГРОКА (входы/выходы, смерти), а не к объекту.
     * {@code /gl lookup} — да; {@code /gl inspect} по одной клетке — нет, иначе логин на этой
     * координате вытесняет настоящую историю блока.
     */
    public boolean includePlayerEvents = true;

    /** Радиус «global» — без пространственных границ (бокс раскрыт на весь диапазон). */
    public boolean globalRadius = false;
    /** Все измерения — без фильтра по уровню (для lookup; для rollback раскрывается в задание на мир). */
    public boolean allWorlds = false;

    /** Фильтр по source_type (например "tnt", "create:deployer"); null = любой. */
    @Nullable public String sourceType;
    /** Только эти материалы (имена в формате GL, без minecraft:); пусто = все. */
    public final java.util.List<String> includeMaterials = new java.util.ArrayList<>();
    /** Исключить эти материалы; пусто = ничего не исключать. */
    public final java.util.List<String> excludeMaterials = new java.util.ArrayList<>();

    /** Фильтр действий ({@code a:place}/{@code a:break}/…): только эти категории; пусто = все. */
    public final java.util.Set<String> actionsInclude = new java.util.HashSet<>();
    /** Исключить эти категории действий ({@code a:!use}); пусто = ничего не исключать. */
    public final java.util.Set<String> actionsExclude = new java.util.HashSet<>();

    public void setBox(int cx, int cy, int cz, int r) {
        this.centerX = cx; this.centerY = cy; this.centerZ = cz;
        this.radius = r;
        this.minX = cx - r; this.maxX = cx + r;
        this.minY = Math.max(-64, cy - r); this.maxY = Math.min(320, cy + r);
        this.minZ = cz - r; this.maxZ = cz + r;
    }

    /** Раскрыть бокс на весь диапазон координат (радиус «global»). */
    public void setGlobalBox() {
        this.globalRadius = true;
        this.radius = -1;
        this.minX = Integer.MIN_VALUE; this.maxX = Integer.MAX_VALUE;
        this.minY = Integer.MIN_VALUE; this.maxY = Integer.MAX_VALUE;
        this.minZ = Integer.MIN_VALUE; this.maxZ = Integer.MAX_VALUE;
    }

    /** Полная копия (для раскрытия allWorlds в отдельные задания на каждый мир). */
    public RollbackFilter copy() {
        RollbackFilter c = new RollbackFilter();
        c.timeFrom = timeFrom; c.timeTo = timeTo; c.levelName = levelName;
        c.playerUuid = playerUuid;
        c.playerNames.addAll(playerNames);
        c.excludePlayerNames.addAll(excludePlayerNames);
        c.minX = minX; c.minY = minY; c.minZ = minZ;
        c.maxX = maxX; c.maxY = maxY; c.maxZ = maxZ;
        c.radius = radius; c.centerX = centerX; c.centerY = centerY; c.centerZ = centerZ;
        c.includeBlocks = includeBlocks; c.includeItems = includeItems;
        c.includePlayerEvents = includePlayerEvents;
        c.globalRadius = globalRadius; c.allWorlds = allWorlds;
        c.sourceType = sourceType;
        c.includeMaterials.addAll(includeMaterials);
        c.excludeMaterials.addAll(excludeMaterials);
        c.actionsInclude.addAll(actionsInclude);
        c.actionsExclude.addAll(actionsExclude);
        return c;
    }
}
