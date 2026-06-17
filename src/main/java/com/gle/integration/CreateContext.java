package com.gle.integration;

import java.util.ArrayDeque;

/**
 * Контекст текущей операции Create на потоке. Маленькие миксины в Create (сборка/разборка
 * контрапции, выстрел схематической пушки) кладут сюда метку источника на время своей работы,
 * а универсальный перехватчик установки блоков ({@code LevelChunkMixin}) читает её и логирует
 * каждое реальное изменение блока с правильной позицией и источником {@code create:*}.
 * <p>
 * Стек (а не одно значение) — потому что операции Create вложены (контрапция при разборке
 * вызывает разборку под-контрапций).
 */
public final class CreateContext {

    private static final ThreadLocal<ArrayDeque<String>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private CreateContext() {}

    public static void push(String source) {
        STACK.get().push(source);
    }

    public static void pop() {
        ArrayDeque<String> d = STACK.get();
        if (!d.isEmpty()) d.pop();
    }

    /** Текущий источник или null, если операции Create на этом потоке нет. */
    public static String current() {
        ArrayDeque<String> d = STACK.get();
        return d.isEmpty() ? null : d.peek();
    }

    public static boolean isActive() {
        return !STACK.get().isEmpty();
    }
}
