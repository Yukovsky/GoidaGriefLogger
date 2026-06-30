package com.gle.core.rollback;

import java.util.ArrayList;
import java.util.List;

/**
 * Канонизация и применение фильтра действий ({@code a:}/{@code action:}) для lookup и rollback.
 * <p>
 * Команды задают действия семантически (как в CoreProtect): {@code a:place}, {@code a:break},
 * {@code a:!use} (исключить). Здесь алиасы (в т.ч. форма GriefLogger {@code [CREATE]}) сводятся к
 * каноническим категориям, по которым фильтруются строки lookup ({@link RollbackFilter#actionsInclude}/
 * {@link RollbackFilter#actionsExclude}) и сужается набор кодов действий для rollback.
 * <p>
 * Категории: {@code break}, {@code place}, {@code use}, {@code kill}, {@code container},
 * {@code session}, {@code sign}, {@code frame}, {@code death}.
 */
public final class ActionFilters {

    private ActionFilters() {}

    public static final String BREAK = "break";
    public static final String PLACE = "place";
    public static final String USE = "use";
    public static final String KILL = "kill";
    public static final String CONTAINER = "container";
    public static final String SESSION = "session";
    public static final String SIGN = "sign";
    public static final String FRAME = "frame";
    public static final String DEATH = "death";

    /**
     * Свести пользовательский токен к канонической категории (или {@code null}, если неизвестен).
     * Принимает краткие/полные имена, знаки {@code +}/{@code -} и форму GriefLogger {@code [CREATE]}.
     */
    public static String canon(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase();
        if (t.startsWith("[") && t.endsWith("]") && t.length() >= 2) t = t.substring(1, t.length() - 1);
        return switch (t) {
            case "break", "broke", "destroy", "remove", "rem", "-", "dig" -> BREAK;
            case "place", "placed", "create", "add", "+", "build" -> PLACE;
            case "use", "used", "click", "interact", "open" -> USE;
            case "kill", "killed", "death_entity" -> KILL;
            case "container", "chest", "item", "items", "transaction", "inv" -> CONTAINER;
            case "session", "login", "logout", "join", "leave", "connect" -> SESSION;
            case "sign" -> SIGN;
            case "frame", "itemframe", "item_frame" -> FRAME;
            case "death", "died", "die" -> DEATH;
            default -> null;
        };
    }

    /** Проходит ли категория текущий фильтр действий (include — белый список, exclude — чёрный). */
    public static boolean allows(RollbackFilter f, String category) {
        if (!f.actionsInclude.isEmpty() && !f.actionsInclude.contains(category)) return false;
        return !f.actionsExclude.contains(category);
    }

    /**
     * Коды действий блоков для rollback из подмножества {@code {0=break, 1=place}}, прошедшие фильтр.
     * Rollback реверсирует только сломы/постановки; пустой список = ни одного (блоки не трогаем).
     */
    public static List<Integer> allowedRollbackBlockActions(RollbackFilter f) {
        List<Integer> out = new ArrayList<>(2);
        if (allows(f, BREAK)) out.add(0);
        if (allows(f, PLACE)) out.add(1);
        return out;
    }

    /** Категория строки блока по коду действия GriefLogger ({@link com.gle.core.GLActions}). */
    public static String blockCategory(int action) {
        return switch (action) {
            case 0 -> BREAK;
            case 1 -> PLACE;
            case 2 -> USE;
            case 3 -> KILL;
            default -> "?";
        };
    }
}
