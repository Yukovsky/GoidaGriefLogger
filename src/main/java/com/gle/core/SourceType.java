package com.gle.core;

/**
 * Константы для колонки {@code blocks.source_type} (добавляется GLE через ALTER TABLE).
 * Источник не-игрового изменения мира. Для игрока остаётся {@code null} (это пишет сам GriefLogger).
 * Произвольные модовые источники имеют вид {@code <modid>:<entity_type>}.
 */
public final class SourceType {

    private SourceType() {}

    // Взрывы
    public static final String TNT            = "tnt";
    public static final String CREEPER        = "creeper";
    public static final String BED            = "bed";
    public static final String RESPAWN_ANCHOR = "respawn_anchor";
    public static final String END_CRYSTAL    = "end_crystal";
    public static final String GHAST          = "ghast";
    public static final String WITHER_SKULL   = "wither_skull";
    public static final String WITHER         = "wither";
    public static final String WIND_CHARGE    = "wind_charge";
    public static final String EXPLOSION_OTHER = "explosion";

    // Активация (нажимные плиты, тропвайр и т.п.) — то, что не пишет сам GriefLogger
    public static final String ACTIVATE       = "activate";
    // Появление могилы (мод gravestone) при смерти игрока
    public static final String GRAVE          = "grave";

    // Механизмы
    public static final String PISTON         = "piston";
    public static final String PISTON_DESTROY = "piston_destroy";
    public static final String HOPPER         = "hopper";
    public static final String GRAVITY        = "gravity";

    // Экология (Фаза 3)
    public static final String FIRE    = "fire";
    public static final String LAVA    = "lava";
    public static final String WATER   = "water";
    public static final String MELTING = "melting";
    public static final String SCULK   = "sculk";

    // Create
    public static final String CREATE_DEPLOYER      = "create:deployer";
    public static final String CREATE_CONTRAPTION   = "create:contraption";
    public static final String CREATE_SCHEMATICANNON = "create:schematicannon";

    public static final String UNKNOWN = "unknown";

    /** Префикс источника-сущности: {@code entity:enderman}. */
    public static String entity(String entityId) {
        return "entity:" + entityId;
    }
}
