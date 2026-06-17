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

    /** Имя материала/предмета в формате GriefLogger. */
    public static String normalize(ResourceLocation location) {
        if (location == null) return "air";
        return location.toString().replace("minecraft:", "");
    }
}
