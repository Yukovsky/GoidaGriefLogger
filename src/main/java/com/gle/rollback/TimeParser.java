package com.gle.rollback;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор человекочитаемых длительностей: {@code s m h d w} и их комбинаций ({@code 1d12h}).
 */
public final class TimeParser {

    private TimeParser() {}

    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])");

    /** @return длительность в миллисекундах, либо -1 при ошибке разбора. */
    public static long parseDurationMs(String input) {
        if (input == null || input.isBlank()) return -1;
        Matcher m = PART.matcher(input.toLowerCase());
        long total = 0;
        int matchedEnd = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            long unitMs = switch (m.group(2)) {
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> 0L;
            };
            total += n * unitMs;
            matchedEnd = m.end();
        }
        if (!any || matchedEnd != input.length()) return -1; // мусор в строке
        return total;
    }
}
