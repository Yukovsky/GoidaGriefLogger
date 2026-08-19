package com.gle.integration.create;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто управляет контрапцией Create, если это вообще известно.
 * <p>
 * Сборка и разборка контрапции меняют мир напрямую, и всё это писалось на системного
 * пользователя {@code [CREATE]}: кто именно собрал машину или вёл поезд, разбросавший чужие
 * блоки, в логе не было. Там, где Create знает управляющего игрока (поезда, управляемые
 * контрапции), берём его.
 * <p>
 * Только рефлексия: Create не входит в зависимости мода, миксины работают по строковым таргетам.
 * Если поля или метода нет — молча отдаём {@code null}, и атрибуция остаётся прежней, системной.
 * Ложного обвинения это не создаёт: «ближайший игрок» и подобные догадки here не используются.
 */
public final class ContraptionOwner {

    private ContraptionOwner() {}

    /** Кэш «класс → найденный доступ», чтобы не искать рефлексией на каждую сборку. */
    private static final Map<Class<?>, Field> ENTITY_FIELD = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> CONTROLLER = new ConcurrentHashMap<>();

    private static final Field NO_FIELD;
    static {
        Field placeholder;
        try {
            placeholder = ContraptionOwner.class.getDeclaredField("NO_FIELD");
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
        NO_FIELD = placeholder;
    }

    /**
     * @param contraption экземпляр {@code com.simibubi.create.content.contraptions.Contraption}
     * @return uuid управляющего игрока строкой, либо {@code null}, если он неизвестен
     */
    @Nullable
    public static String uuidOf(Object contraption) {
        if (contraption == null) return null;
        try {
            Object entity = entityOf(contraption);
            if (entity == null) return null;

            Optional<Method> controller = CONTROLLER.computeIfAbsent(entity.getClass(), cls -> {
                for (String name : new String[]{"getControllingPlayer", "getController"}) {
                    try {
                        Method m = cls.getMethod(name);
                        m.setAccessible(true);
                        return Optional.of(m);
                    } catch (Throwable ignored) {
                        // пробуем следующее имя
                    }
                }
                return Optional.empty();
            });
            if (controller.isEmpty()) return null;

            Object value = controller.get().invoke(entity);
            if (value instanceof Optional<?> opt) value = opt.orElse(null);
            if (value instanceof UUID uuid) return uuid.toString();
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Поле {@code entity} контрапции — ссылка на сущность-носитель. */
    @Nullable
    private static Object entityOf(Object contraption) throws Exception {
        Field field = ENTITY_FIELD.computeIfAbsent(contraption.getClass(), cls -> {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("entity");
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                    // ищем выше по иерархии
                }
            }
            return NO_FIELD; // «не нашли» — запоминаем, чтобы не искать снова
        });
        return field == NO_FIELD ? null : field.get(contraption);
    }
}
