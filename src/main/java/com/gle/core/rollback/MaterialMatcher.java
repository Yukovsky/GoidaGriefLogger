package com.gle.core.rollback;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Сопоставление токенов {@code include:}/{@code exclude:} с именами материалов GriefLogger
 * (формат без {@code minecraft:}; префиксы модов сохранены, напр. {@code create:item_drain}).
 * <p>
 * Семантика «умное + явный wildcard» — едина для lookup, rollback, restore и preview, иначе
 * история и фактический откат разъехались бы:
 * <ul>
 *   <li>без {@code *}: точное имя ({@code create:item_drain}) ИЛИ имя с опущенным modid
 *       ({@code item_drain} → подходит к {@code *:item_drain}); ванильное {@code stone}
 *       совпадает только со {@code stone}, но НЕ с {@code cobblestone};</li>
 *   <li>с {@code *}: подстрока/маска ({@code *drain*}, {@code create:*}).</li>
 * </ul>
 * Спецсимволы LIKE ({@code %}, {@code _}) в токене экранируются, поэтому {@code item_drain}
 * не превращается в маску. Звёздочка пользователя — единственный wildcard.
 */
public final class MaterialMatcher {

    private MaterialMatcher() {}

    // Экранирующий символ для LIKE. НЕ backslash: в MySQL/MariaDB '\' внутри строкового литерала
    // сам по себе экранирующий, и 'ESCAPE '\'' ломает синтаксис. '!' не встречается в ResourceLocation.
    private static final char ESC_CH = '!';
    private static final String ESC = " ESCAPE '" + ESC_CH + "'";

    public static boolean hasWildcard(String token) {
        return token.indexOf('*') >= 0;
    }

    /** Совпадает ли нормализованное имя материала (без {@code minecraft:}) с токеном (in-memory зеркало SQL). */
    public static boolean matches(String name, String token) {
        if (name == null || token == null) return false;
        if (hasWildcard(token)) return name.matches(globToRegex(token));
        return name.equals(token) || name.endsWith(":" + token);
    }

    /** WHERE-фрагмент по колонке материала (например {@code "m.name"}) для include+exclude. */
    public static String whereFragment(String col, List<String> include, List<String> exclude) {
        StringBuilder sql = new StringBuilder();
        if (!include.isEmpty()) sql.append(" AND ").append(orPredicate(col, include));
        if (!exclude.isEmpty())
            sql.append(" AND (").append(col).append(" IS NULL OR NOT ").append(orPredicate(col, exclude)).append(")");
        return sql.toString();
    }

    /** {@code (col … OR col …)} — дизъюнкция условий по списку токенов. */
    public static String orPredicate(String col, List<String> tokens) {
        StringBuilder sql = new StringBuilder("(");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append(condition(col, tokens.get(i)));
        }
        return sql.append(")").toString();
    }

    private static String condition(String col, String token) {
        if (hasWildcard(token)) return col + " LIKE ?" + ESC;
        return "(" + col + " = ? OR " + col + " LIKE ?" + ESC + ")";
    }

    /** Параметры для {@link #whereFragment} в порядке include, затем exclude. */
    public static List<String> params(List<String> include, List<String> exclude) {
        List<String> out = new ArrayList<>();
        out.addAll(paramsFor(include));
        out.addAll(paramsFor(exclude));
        return out;
    }

    /** Параметры одного списка токенов (порядок совпадает с {@link #orPredicate}). */
    public static List<String> paramsFor(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String token : tokens) {
            if (hasWildcard(token)) {
                out.add(escapeLike(token).replace('*', '%'));
            } else {
                out.add(token);
                out.add("%:" + escapeLike(token));
            }
        }
        return out;
    }

    /** Привязать параметры по порядку, вернуть следующий индекс. */
    public static int bind(PreparedStatement ps, List<String> params, int i) throws SQLException {
        for (String p : params) ps.setString(i++, p);
        return i;
    }

    private static String escapeLike(String s) {
        // Сначала экранируем сам экранирующий символ, затем спецсимволы LIKE.
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String globToRegex(String token) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '*') re.append(".*");
            else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return re.toString();
    }
}
