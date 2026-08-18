package com.gle.core;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("GLE/GriefContext");

    /** Атрибуция изменения: значение колонки {@code source_type} и имя системного пользователя. */
    public record Attribution(String sourceType, String systemUser) {}

    private static final ThreadLocal<ArrayDeque<Attribution>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private GriefContext() {}

    /**
     * Открыть область атрибуции. Возвращает {@link Scope} — вызывающим следует писать
     * {@code try (var ignored = GriefContext.push(...)) { ... }}: без {@code finally} исключение
     * между push и pop оставляло запись в стеке ГЛАВНОГО потока, и она подмешивалась во все
     * последующие тики, приписывая чужие изменения этому источнику.
     */
    public static Scope push(String sourceType, String systemUser) {
        STACK.get().push(new Attribution(sourceType, systemUser));
        return POP;
    }

    /** Область атрибуции: закрытие снимает верхний элемент стека. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    private static final Scope POP = GriefContext::pop;

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

    // Стек, а не одна ячейка: тик сущности A может вложенно запустить тик сущности B, и прежний
    // ENTITY.set(B) затирал A, а exitEntity() делал remove() вместо возврата к A — после чего
    // собственные изменения A логировались как UNKNOWN.
    private static final ThreadLocal<ArrayDeque<Entity>> ENTITY = ThreadLocal.withInitial(ArrayDeque::new);

    public static void enterEntity(Entity e) {
        ENTITY.get().push(e);
    }

    /**
     * Снять контекст сущности. Снимаем ТОЛЬКО если наверху действительно она: HEAD-инжект может
     * быть выключен конфигом, а RETURN — сработать, и безусловный pop съел бы контекст родителя
     * при вложенных тиках.
     */
    public static void exitEntity(Entity expected) {
        ArrayDeque<Entity> d = ENTITY.get();
        if (!d.isEmpty() && d.peek() == expected) d.pop();
    }

    /** Сущность, чей AI-тик сейчас выполняется на потоке, или null. */
    @Nullable
    public static Entity entity() {
        return ENTITY.get().peek();
    }

    /**
     * Страховка от утечки контекста, вызывается раз в тик сервера.
     * <p>
     * Инжекты мод-миксинов ставят контекст на HEAD и снимают на RETURN, но {@code @At("RETURN")}
     * НЕ срабатывает при выходе по исключению — а также push и pop могут разойтись, если конфиг
     * переключили между ними. Утёкшая запись живёт на главном потоке и приписывает свой источник
     * всем последующим изменениям блоков. К концу тика стек обязан быть пуст; если нет — чистим,
     * ограничивая любую утечку одним тиком вместо «до перезапуска сервера».
     */
    public static void sweepLeaks() {
        ArrayDeque<Attribution> attrs = STACK.get();
        ArrayDeque<Entity> entities = ENTITY.get();
        if (attrs.isEmpty() && entities.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastLeakWarn > 60_000L) {
            lastLeakWarn = now;
            LOGGER.warn("Контекст атрибуции не был снят к концу тика (attributions={}, entities={}) — сбрасываем. "
                    + "Вероятно, изменение мира вышло по исключению.", attrs.size(), entities.size());
        }
        attrs.clear();
        entities.clear();
    }

    private static volatile long lastLeakWarn = 0;
}
