package se.grynna.pager.ui.time;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Pure segment arithmetic of the {@code yyyy-MM-dd HH:mm:ss} editor: stepping with clock carry,
 * month-day clamping, the digit typing model and the column ranges of each segment.
 */
public final class DateTimeSegments {

    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final int LENGTH = PATTERN.length();
    public static final int MIN_YEAR = 1000;
    public static final int MAX_YEAR = 9999;

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    /** A segment of the pattern; {@code start}/{@code end} are its half-open column range in {@link #format}. */
    public enum Segment {
        YEAR(0, 4), MONTH(5, 7), DAY(8, 10), HOUR(11, 13), MINUTE(14, 16), SECOND(17, 19);

        private final int start;
        private final int end;

        Segment(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public int digits() {
            return end - start;
        }

        /** The segment to the right, or this one when last. */
        public Segment next() {
            return this == SECOND ? this : values()[ordinal() + 1];
        }

        /** The segment to the left, or this one when first. */
        public Segment previous() {
            return this == YEAR ? this : values()[ordinal() - 1];
        }

        /** The next larger unit (PgUp/PgDn); the year steps by ten years instead. */
        public Segment larger() {
            return previous();
        }

        public static Segment first() {
            return YEAR;
        }

        public static Segment last() {
            return SECOND;
        }
    }

    private DateTimeSegments() {}

    public static String format(LocalDateTime t) {
        return FORMAT.format(t);
    }

    /** Seconds precision: what the editor shows and edits. */
    public static LocalDateTime truncate(LocalDateTime t) {
        return t.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * The segment at a column of {@link #format}; a separator column belongs to the segment on its left, a column
     * past the end to the last segment.
     */
    public static Segment segmentAt(int column) {
        for (Segment s : Segment.values()) {
            if (column < s.end()) {
                return s;
            }
            if (column == s.end() && s != Segment.SECOND) {
                return s;
            }
        }
        return Segment.SECOND;
    }

    /** The value of a segment, e.g. 5 for the month of May. */
    public static int value(LocalDateTime t, Segment s) {
        return switch (s) {
            case YEAR -> t.getYear();
            case MONTH -> t.getMonthValue();
            case DAY -> t.getDayOfMonth();
            case HOUR -> t.getHour();
            case MINUTE -> t.getMinute();
            case SECOND -> t.getSecond();
        };
    }

    /**
     * Steps a segment by {@code delta} units, carrying like a clock (12:59:59 + 1 s → 13:00:00, Dec 31 + 1 d → Jan
     * 1). Month and year steps clamp the day to the target month (Jan 31 + 1 month → Feb 28/29). A step that would
     * leave the four-digit years returns {@code t} unchanged.
     */
    public static LocalDateTime step(LocalDateTime t, Segment s, int delta) {
        LocalDateTime r = switch (s) {
            case YEAR -> t.plusYears(delta);
            case MONTH -> t.plusMonths(delta);
            case DAY -> t.plusDays(delta);
            case HOUR -> t.plusHours(delta);
            case MINUTE -> t.plusMinutes(delta);
            case SECOND -> t.plusSeconds(delta);
        };
        return r.getYear() < MIN_YEAR || r.getYear() > MAX_YEAR ? t : r;
    }

    /** PgUp/PgDn: steps the next larger unit; on the year it steps ten years. */
    public static LocalDateTime stepLarger(LocalDateTime t, Segment s, int delta) {
        return s == Segment.YEAR ? step(t, Segment.YEAR, 10 * delta) : step(t, s.larger(), delta);
    }

    /** Sets a segment to {@code value}, clamped to its valid range (and the day to the month's length). */
    public static LocalDateTime withClamped(LocalDateTime t, Segment s, int value) {
        return switch (s) {
            case YEAR -> t.withYear(clamp(value, MIN_YEAR, MAX_YEAR));
            case MONTH -> t.withMonth(clamp(value, 1, 12));
            case DAY -> t.withDayOfMonth(clamp(value, 1, YearMonth.from(t).lengthOfMonth()));
            case HOUR -> t.withHour(clamp(value, 0, 23));
            case MINUTE -> t.withMinute(clamp(value, 0, 59));
            case SECOND -> t.withSecond(clamp(value, 0, 59));
        };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static boolean isSeparator(char c) {
        return c == '-' || c == ':' || c == ' ';
    }

    /**
     * The digit typing model, immutable. Digits fill {@link #buffer} of the focused segment; a full segment is
     * committed (clamped) and focus advances. A separator commits a partial segment and advances, unless the
     * segment was just auto-advanced into (so {@code 2017-05-01} and {@code 20170501} both work). A partial year of
     * one or two digits means 20xx.
     *
     * @param value        the edited time, seconds precision
     * @param segment      the focused segment
     * @param buffer       digits typed into the focused segment, not yet committed
     * @param autoAdvanced whether focus has just moved here because the previous segment filled up
     */
    public record TypingState(LocalDateTime value, Segment segment, String buffer, boolean autoAdvanced) {

        public static TypingState of(LocalDateTime value, Segment segment) {
            return new TypingState(truncate(value), segment, "", false);
        }

        public boolean isTyping() {
            return !buffer.isEmpty();
        }

        public TypingState digit(char c) {
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("not a digit: " + c);
            }
            String typed = buffer + c;
            if (typed.length() < segment.digits()) {
                return new TypingState(value, segment, typed, false);
            }
            LocalDateTime committed = withClamped(value, segment, Integer.parseInt(typed));
            boolean advances = segment != Segment.last();
            return new TypingState(committed, segment.next(), "", advances);
        }

        /** {@code -}, {@code :} or space: complete the segment and move on. */
        public TypingState separator() {
            if (buffer.isEmpty() && autoAdvanced) {
                return new TypingState(value, segment, "", false);
            }
            return commit().moveTo(segment.next());
        }

        /** Commits a partial buffer (clamped), keeping the focused segment. */
        public TypingState commit() {
            if (buffer.isEmpty()) {
                return autoAdvanced ? new TypingState(value, segment, "", false) : this;
            }
            int n = Integer.parseInt(buffer);
            if (segment == Segment.YEAR && buffer.length() <= 2) {
                n += 2000;
            }
            return new TypingState(withClamped(value, segment, n), segment, "", false);
        }

        /** Commits and focuses {@code target}. */
        public TypingState moveTo(Segment target) {
            TypingState c = commit();
            return new TypingState(c.value, target, "", false);
        }

        /** Backspace: drops the last typed digit; with nothing typed, moves focus to the previous segment. */
        public TypingState backspace() {
            return buffer.isEmpty()
                    ? moveTo(segment.previous())
                    : new TypingState(value, segment, buffer.substring(0, buffer.length() - 1), false);
        }

        /** A new value, e.g. after a step, keeping the focused segment and dropping a partial buffer. */
        public TypingState withValue(LocalDateTime newValue) {
            return new TypingState(truncate(newValue), segment, "", false);
        }
    }
}
