package se.grynna.pager.ui;

import java.time.Duration;

/**
 * Compact human text for a duration, e.g. for gap rules ("14m later").
 * <ul>
 * <li>&lt; 10 s: one decimal, truncated: {@code 3.2s}, {@code 0.4s}, {@code 9.9s}</li>
 * <li>&lt; 1 min: whole seconds: {@code 42s}</li>
 * <li>&lt; 1 h: whole minutes: {@code 14m}</li>
 * <li>&lt; 1 day: hours and minutes, the minutes omitted when zero: {@code 2h 10m}, {@code 2h}</li>
 * <li>&ge; 1 day: days, plus hours when non-zero: {@code 3d}, {@code 3d 4h}</li>
 * </ul>
 * Every unit is truncated (floor), never rounded up. A negative duration is formatted by its
 * absolute value.
 */
public final class DurationText {

    private static final long SECONDS_PER_MINUTE = 60;
    private static final long SECONDS_PER_HOUR = 3600;
    private static final long SECONDS_PER_DAY = 86400;

    private DurationText() {}

    public static String shortText(Duration d) {
        Duration abs = d.abs();
        long millis = abs.toMillis();
        if (millis < 10_000) {
            long tenths = millis / 100;
            return (tenths / 10) + "." + (tenths % 10) + "s";
        }
        long seconds = abs.getSeconds();
        if (seconds < SECONDS_PER_MINUTE) {
            return seconds + "s";
        }
        if (seconds < SECONDS_PER_HOUR) {
            return seconds / SECONDS_PER_MINUTE + "m";
        }
        if (seconds < SECONDS_PER_DAY) {
            long hours = seconds / SECONDS_PER_HOUR;
            long minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE;
            return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
        }
        long days = seconds / SECONDS_PER_DAY;
        long hours = seconds % SECONDS_PER_DAY / SECONDS_PER_HOUR;
        return hours == 0 ? days + "d" : days + "d " + hours + "h";
    }
}
