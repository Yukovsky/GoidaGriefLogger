package com.gle.core;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Контекст не-игрового изменения мира на текущем потоке. Ядро-нейтральный механизм атрибуции:
 * маленькие миксины в ванильную/модовую логику, которая меняет блоки прямым {@code setBlock}
 * без событий (падающие блоки, гриферство мобов, контрапции/пушка Create), кладут сюда атрибуцию
 * на время своей работы, а универсальный перехватчик {@code LevelChunkMixin} читает её и логирует
 * реальное изменение блока с правильной позицией, {@code source_type} и системным пользователем.
 * <p>
 * Лежит в {@code core}, а не в {@code integration}: это платформо- и мод-нейтральный примитив,
 * которым пользуются и ванильные миксины, и модули интеграций (например {@code create}).
 * <p>
 * Стек — на случай вложенности (например, падающий блок при приземлении ломает другой блок).
 */
public final class GriefContext {

    /** Атрибуция изменения: значение колонки {@code source_type} и имя системного пользователя. */
    public record Attribution(String sourceType, String systemUser) {}

    private static final ThreadLocal<ArrayDeque<Attribution>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private GriefContext() {}

    public static void push(String sourceType, String systemUser) {
        STACK.get().push(new Attribution(sourceType, systemUser));
    }

    public static void pop() {
        ArrayDeque<Attribution> d = STACK.get();
        if (!d.isEmpty()) d.pop();
    }

    /** Текущая атрибуция или null, если не-игрового контекста на потоке нет. */
    @Nullable
    public static Attribution current() {
        ArrayDeque<Attribution> d = STACK.get();
        return d.isEmpty() ? null : d.peek();
    }

    public static boolean isActive() {
        return !STACK.get().isEmpty();
    }

    // --- Контекст AI-тика моба (гриферство) ----------------------------------
    // Храним саму сущность (без аллокаций на каждый тик), атрибуцию резолвим лениво
    // в LevelChunkMixin только когда блок реально изменился.

    private static final ThreadLocal<Entity> ENTITY = new ThreadLocal<>();

    public static void enterEntity(Entity e) {
        ENTITY.set(e);
    }

    public static void exitEntity() {
        ENTITY.remove();
    }

    /** Сущность, чей AI-тик сейчас выполняется на потоке, или null. */
    @Nullable
    public static Entity entity() {
        return ENTITY.get();
    }
}
