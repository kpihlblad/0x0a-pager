package se.grynna.pager.ui.time;

import se.grynna.pager.splunk.SearchBoundaries;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The preset chip rows of the time panel, computed from the AT value, now and the zone. A preset end after
 * now becomes {@code null} ("now"); a start before {@link SearchBoundaries#MIN_MINIMUM_BOUNDARY} becomes that
 * minimum ("1971").
 */
public final class TimePresets {

    public static final Instant START = SearchBoundaries.MIN_MINIMUM_BOUNDARY;

    private static final DateTimeFormatter AT_TITLE = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** A row of chips under a small caps title. */
    public record Row(String title, List<Preset> presets) {}

    /**
     * One chip.
     *
     * @param from the range start, {@link #START} for "1971" (the minimum boundary's year)
     * @param to   the range end, {@code null} when it follows now
     */
    public record Preset(String label, Instant from, Instant to) {
        public boolean fromIsStart() {
            return START.equals(from);
        }

        public boolean toIsNow() {
            return to == null;
        }
    }

    private TimePresets() {}

    public static List<Row> rows(Instant at, Instant now, ZoneId zone) {
        return List.of(around(at, now, zone), thisRow(at, now, zone), last(now), fromAt(at, now, zone));
    }

    static Row around(Instant at, Instant now, ZoneId zone) {
        List<Preset> p = new ArrayList<>();
        for (String s : List.of("1m", "5m", "15m", "30m", "1h", "3h", "12h", "1d")) {
            Duration d = duration(s);
            p.add(preset("±" + s, at.minus(d), at.plus(d), now));
        }
        return new Row("AROUND " + AT_TITLE.format(at.atZone(zone)), List.copyOf(p));
    }

    static Row thisRow(Instant at, Instant now, ZoneId zone) {
        LocalDateTime t = LocalDateTime.ofInstant(at, zone);
        List<Preset> p = new ArrayList<>();
        p.add(bucket("minute", t.truncatedTo(ChronoUnit.MINUTES), s -> s.plusMinutes(1), zone, now));
        p.add(bucket("5 min", floorMinutes(t, 5), s -> s.plusMinutes(5), zone, now));
        p.add(bucket("15 min", floorMinutes(t, 15), s -> s.plusMinutes(15), zone, now));
        p.add(bucket("hour", t.truncatedTo(ChronoUnit.HOURS), s -> s.plusHours(1), zone, now));
        LocalDateTime day = t.truncatedTo(ChronoUnit.DAYS);
        p.add(bucket("4 hours", day.withHour(t.getHour() / 4 * 4), s -> s.plusHours(4), zone, now));
        p.add(bucket("day", day, s -> s.plusDays(1), zone, now));
        p.add(bucket("week", day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), s -> s.plusWeeks(1), zone,
                now));
        p.add(bucket("month", day.withDayOfMonth(1), s -> s.plusMonths(1), zone, now));
        return new Row("THIS", List.copyOf(p));
    }

    static Row last(Instant now) {
        List<Preset> p = new ArrayList<>();
        for (String s : List.of("5m", "15m", "30m", "1h", "4h", "12h", "24h", "2d", "7d", "30d")) {
            p.add(preset(s, now.minus(duration(s)), null, now));
        }
        return new Row("LAST", List.copyOf(p));
    }

    static Row fromAt(Instant at, Instant now, ZoneId zone) {
        List<Preset> p = new ArrayList<>();
        for (String s : List.of("5m", "15m", "1h", "1d")) {
            p.add(preset("+" + s, at, at.plus(duration(s)), now));
        }
        p.add(preset("AT → now", at, null, now));
        p.add(preset(SearchBoundaries.MIN_BOUNDARY_YEAR + " → AT", START, at, now));
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant todayStart = today.atStartOfDay(zone).toInstant();
        p.add(preset("today", todayStart, today.plusDays(1).atStartOfDay(zone).toInstant(), now));
        p.add(preset("yesterday", today.minusDays(1).atStartOfDay(zone).toInstant(), todayStart, now));
        p.add(preset("everything", START, null, now));
        return new Row("FROM AT", List.copyOf(p));
    }

    private static Preset bucket(String label, LocalDateTime start, UnaryOperator<LocalDateTime> next, ZoneId zone,
            Instant now) {
        return preset(label, start.atZone(zone).toInstant(), next.apply(start).atZone(zone).toInstant(), now);
    }

    private static LocalDateTime floorMinutes(LocalDateTime t, int minutes) {
        LocalDateTime hour = t.truncatedTo(ChronoUnit.HOURS);
        return hour.withMinute(t.getMinute() / minutes * minutes);
    }

    private static Preset preset(String label, Instant from, Instant to, Instant now) {
        Instant f = from.isBefore(START) ? START : from;
        Instant t = to == null || to.isAfter(now) ? null : to;
        return new Preset(label, f, t);
    }

    private static Duration duration(String spec) {
        long n = Long.parseLong(spec.substring(0, spec.length() - 1));
        return switch (spec.charAt(spec.length() - 1)) {
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException(spec);
        };
    }
}
