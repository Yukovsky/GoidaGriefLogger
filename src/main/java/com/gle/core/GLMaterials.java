package com.gle.core;

import net.minecraft.resources.ResourceLocation;

/**
 * Нормализация имён материалов/сущностей под формат GriefLogger.
 * <p>
 * КРИТИЧНО: GriefLogger хранит имена в таблице {@code materials} БЕЗ префикса {@code minecraft:}
 * (код GL делает {@code itemLocation.toString().replace("minecraft:", "")}). Префиксы других
 * модов сохраняются (например {@code create:cogwheel}). Если GLE не повторит эту нормализацию,
 * появятся дубли {@code stone}/{@code minecraft:stone} и инспектор/роллбек разъедутся.
 */
public final class GLMaterials {

    private GLMaterials() {}

    private static final String VANILLA = "minecraft:";

    /** Имя материала/предмета в формате GriefLogger. */
    public static String normalize(ResourceLocation location) {
        if (location == null) return "air";
        String id = location.toString();
        // Именно startsWith, а не replace: replace вырезал подстроку в ЛЮБОЙ позиции, и id мода,
        // содержащий "minecraft:", искажался (напр. "notminecraft:foo" -> "notfoo").
        return id.startsWith(VANILLA) ? id.substring(VANILLA.length()) : id;
    }
}
