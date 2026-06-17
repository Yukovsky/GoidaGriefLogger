package com.gle.api;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Точка доступа к публичному API GLE. Null-safe: {@link #getApi()} вернёт {@code Optional.empty()},
 * если GLE не установлен/не инициализирован.
 *
 * <pre>{@code
 * GLExtended.getApi().ifPresent(api -> api.logBlockBreak(...));
 * }</pre>
 */
public final class GLExtended {

    @Nullable
    private static volatile GLExtendedApi instance;

    private GLExtended() {}

    public static Optional<GLExtendedApi> getApi() {
        return Optional.ofNullable(instance);
    }

    /** Внутреннее: установка реализации при инициализации мода. */
    public static void setApi(GLExtendedApi api) {
        instance = api;
    }
}
