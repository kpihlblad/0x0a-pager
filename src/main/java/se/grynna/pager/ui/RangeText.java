package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchBoundaries;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Text for the semantic range shown in the blue-bar centre and on the search prompts: {@code 11:00:00 → 12:10:00},
 * {@code 2017-04-28 09:00 → now}, {@code 1971 → 12:10:00}, {@code everything}. Absolute values use the supplied
 * locale-aware formatters. Pure formatting, no Swing.
 */
public final class RangeText {

    /** The designator for the earliest possible time: the year of {@link SearchBoundaries#MIN_MINIMUM_BOUNDARY}. */
    public static final String NOW = "now", START = SearchBoundaries.MIN_BOUNDARY_YEAR, EVERYTHING = "everything";

    private static final String ARROW = " \u2192 ";

    private RangeText() {}

    /** {@code 1971 → end}, or {@code everything} when the range runs from the first boundary to now. */
    public static String range(Instant start, Instant end, Instant anchor, Instant now, ZoneId zone,
            DateTimeFormatter time, DateTimeFormatter minute, DateTimeFormatter second) {
        if (isStart(start) && isNow(end, now)) {
            return EVERYTHING;
        }
        Instant reference = anchor != null ? anchor : end;
        // A start close to now is still a time: "now → now" says nothing.
        String from = isNow(start, now)
                ? absolute(start, reference, zone, time, minute, second)
                : endpoint(start, reference, now, zone, time, minute, second);
        return from + ARROW + endpoint(end, reference, now, zone, time, minute, second);
    }

    /**
     * One endpoint: {@code now}, the minimum's year ({@code 1971}), time only on the anchor's local date, otherwise
     * date and minute (seconds kept when not :00). A null anchor means the endpoint's own date.
     */
    public static String endpoint(Instant t, Instant anchor, Instant now, ZoneId zone, DateTimeFormatter time,
            DateTimeFormatter minute, DateTimeFormatter second) {
        if (isNow(t, now)) {
            return NOW;
        }
        if (isStart(t)) {
            return START;
        }
        return absolute(t, anchor, zone, time, minute, second);
    }

    private static String absolute(Instant t, Instant anchor, ZoneId zone, DateTimeFormatter time,
            DateTimeFormatter minute, DateTimeFormatter second) {
        ZonedDateTime local = t.atZone(zone);
        LocalDate anchorDate = anchor != null ? anchor.atZone(zone).toLocalDate() : local.toLocalDate();
        if (local.toLocalDate().equals(anchorDate)) {
            return time.withZone(zone).format(t);
        }
        return (local.getSecond() == 0 ? minute : second).withZone(zone).format(t);
    }

    /**
     * The Y/N button text for widening from {@code edge} to {@code suggested}, without the question mark:
     * {@code 1h earlier (11:00:00)}, {@code back to 1971}, {@code up to now}, {@code 2h later (14:10:00)}.
     */
    public static String step(Instant edge, Instant suggested, Instant anchor, Instant now, ZoneId zone,
            DateTimeFormatter time, DateTimeFormatter minute, DateTimeFormatter second) {
        boolean earlier = suggested.isBefore(edge);
        String absolute = endpoint(suggested, anchor, now, zone, time, minute, second);
        if (earlier && START.equals(absolute)) {
            return "back to " + START;
        }
        if (!earlier && NOW.equals(absolute)) {
            return "up to now";
        }
        String delta = coarse(Duration.between(edge, suggested).abs());
        return delta + (earlier ? " earlier (" : " later (") + absolute + ")";
    }

    /** Both ends in full, then the absolute duration, for tooltips. */
    public static String full(Instant start, Instant end, ZoneId zone, DateTimeFormatter second) {
        return second.withZone(zone).format(start) + ARROW + second.withZone(zone).format(end) + " (\u0394 "
                + SelectionSpanText.duration(Duration.between(start, end), '.') + ")";
    }

    /** Whole units, at most two most significant ones, lower units truncated: {@code 45s}, {@code 1h 30m}, {@code 2d 4h}. */
    static String coarse(Duration d) {
        long seconds = Math.abs(d.getSeconds());
        if (seconds == 0) {
            return "0s";
        }
        long[] values = {seconds / 86_400, seconds % 86_400 / 3_600, seconds % 3_600 / 60, seconds % 60};
        String[] units = {"d", "h", "m", "s"};
        int first = 0;
        while (values[first] == 0) {
            first++;
        }
        String text = values[first] + units[first];
        int second = first + 1;
        if (second < units.length && values[second] != 0) {
            text += " " + values[second] + units[second];
        }
        return text;
    }

    private static boolean isNow(Instant t, Instant now) {
        Duration slack = SearchRangeOffer.LATER_SLACK;
        return !t.isBefore(now.minus(slack)) && !t.isAfter(now.plus(slack));
    }

    private static boolean isStart(Instant t) {
        return !t.isAfter(SearchBoundaries.MIN_MINIMUM_BOUNDARY);
    }
}
