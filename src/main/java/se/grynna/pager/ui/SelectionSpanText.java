package se.grynna.pager.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Text for the time range of a multi-line selection: {@code 12:00:01.200 → 12:02:14.600 (Δ 2m 13.4s)}.
 * Pure formatting, no Swing and no log access.
 */
public final class SelectionSpanText {

    private SelectionSpanText() {}

    /**
     * The absolute length of {@code d}, with units that adapt to its size. Lower units are truncated, never
     * rounded, so 59.96 s reads {@code 59.9s}.
     */
    static String duration(Duration d, char decimalSeparator) {
        Duration abs = d.isNegative() ? d.negated() : d;
        long seconds = abs.getSeconds();
        int nanos = abs.getNano();

        if (seconds == 0) {
            if (nanos == 0) {
                return "0s";
            }
            if (nanos < 1_000) {
                return nanos + "ns";
            }
            if (nanos < 1_000_000) {
                return nanos / 1_000 + "\u00B5s";
            }
            if (nanos < 1_000_000_000) {
                return nanos / 1_000_000 + "ms";
            }
        }
        if (seconds < 60) {
            return seconds + String.valueOf(decimalSeparator) + nanos / 100_000_000 + "s";
        }
        if (seconds < 3_600) {
            return seconds / 60 + "m " + pad2(seconds % 60) + decimalSeparator + nanos / 100_000_000 + "s";
        }
        if (seconds < 86_400) {
            return seconds / 3_600 + "h " + pad2(seconds % 3_600 / 60) + "m " + pad2(seconds % 60) + "s";
        }
        return seconds / 86_400 + "d " + seconds % 86_400 / 3_600 + "h " + pad2(seconds % 3_600 / 60) + "m";
    }

    /**
     * The range from {@code first} to {@code last}, in {@code zone}: time only when both fall on the same local
     * date, date and time otherwise, then the absolute duration in parentheses. The order is kept as given.
     */
    public static String range(Instant first, Instant last, ZoneId zone, DateTimeFormatter dateTime,
            DateTimeFormatter timeOnly, char decimalSeparator) {
        boolean sameDay = first.atZone(zone).toLocalDate().equals(last.atZone(zone).toLocalDate());
        DateTimeFormatter formatter = (sameDay ? timeOnly : dateTime).withZone(zone);
        return formatter.format(first) + " \u2192 " + formatter.format(last) + " (\u0394 "
                + duration(Duration.between(first, last), decimalSeparator) + ")";
    }

    private static String pad2(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
