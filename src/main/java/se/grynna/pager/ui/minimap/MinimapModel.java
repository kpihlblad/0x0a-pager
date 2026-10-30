package se.grynna.pager.ui.minimap;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

/**
 * Pure layout model of the search minimap strip: buckets match positions into slices, maps bookmarks and
 * the day bands to pixel rows, and maps the visible range. No Swing, no threads.
 * <p>
 * The axis is a {@link Span} of {@code long} units: loaded rows for the log view, epoch nanoseconds
 * (UTC, see {@link #nanos(Instant)}) for the Matches view. Half-open {@code [start, endExclusive)}.
 * Positions outside the span are ignored.
 * <p>
 * Slices: {@code max(1, heightPx / sliceHeightPx)} equal slices over the strip. Slice opacity is
 * {@code 0} for an empty slice, otherwise {@code 0.55 + 0.45 * sqrt(count / maxCount)}: the square root keeps
 * sparse slices bright.
 * <p>
 * Bands: the strip is cut into bands of one {@link BandUnit} (day, ISO week, month or year), the finest unit
 * whose bands are at least {@link #MIN_BAND_PX} px tall on average. Odd-indexed bands are shaded
 * (see {@link Band}); the index depends on the date only, so a band keeps its shade as the span grows.
 */
public final class MinimapModel {

    /** Half-open axis range {@code [start, endExclusive)} in axis units. */
    public record Span(long start, long endExclusive) {

        public Span {
            if (endExclusive < start) {
                throw new IllegalArgumentException("endExclusive < start: " + start + ".." + endExclusive);
            }
        }

        public long length() {
            return endExclusive - start;
        }

        public boolean contains(long position) {
            return position >= start && position < endExclusive;
        }

        /** A span over epoch nanoseconds (UTC). */
        public static Span ofInstants(Instant from, Instant toExclusive) {
            return new Span(nanos(from), nanos(toExclusive));
        }
    }

    /** Units of the day bands, finest first. */
    public enum BandUnit {
        DAY, WEEK, MONTH, YEAR;

        /** Whether the local epoch day starts a unit of this kind. Every day starts a day. */
        public boolean startsUnit(long epochDay) {
            LocalDate date = LocalDate.ofEpochDay(epochDay);
            return switch (this) {
                case DAY -> true;
                case WEEK -> date.getDayOfWeek() == DayOfWeek.MONDAY;
                case MONTH -> date.getDayOfMonth() == 1;
                case YEAR -> date.getDayOfYear() == 1;
            };
        }

        /**
         * The index of the unit containing the local epoch day; consecutive units differ by one. Shaded when odd.
         * Weeks: 1970-01-01 was a Thursday, so {@code +3} makes the weeks start on Mondays.
         */
        public long indexOf(long epochDay) {
            LocalDate date = LocalDate.ofEpochDay(epochDay);
            return switch (this) {
                case DAY -> epochDay;
                case WEEK -> Math.floorDiv(epochDay + 3, 7);
                case MONTH -> date.getYear() * 12L + date.getMonthValue() - 1;
                case YEAR -> date.getYear();
            };
        }
    }

    /** A shaded band as pixel rows {@code [top, bottom)}. */
    public record Band(int top, int bottom) {}

    /** Opacity of the sparsest non-empty slice; the densest one is opaque. */
    private static final float MIN_ALPHA = 0.55f;
    /** A band unit is used only when its bands average at least this many pixels. */
    static final int MIN_BAND_PX = 8;

    private final Span span;
    private final int heightPx;
    private final int[] sliceCounts;
    private final float[] alphas;
    private final BandUnit bandUnit;
    private final List<Band> shadedBands;
    private final int[] bookmarkYs;
    private final int visibleFromY;
    private final int visibleToY;

    private MinimapModel(Span span, int heightPx, int[] sliceCounts, float[] alphas, BandUnit bandUnit,
            List<Band> shadedBands, int[] bookmarkYs, int visibleFromY, int visibleToY) {
        this.span = span;
        this.heightPx = heightPx;
        this.sliceCounts = sliceCounts;
        this.alphas = alphas;
        this.bandUnit = bandUnit;
        this.shadedBands = shadedBands;
        this.bookmarkYs = bookmarkYs;
        this.visibleFromY = visibleFromY;
        this.visibleToY = visibleToY;
    }

    /**
     * Build the model.
     *
     * @param span           the axis the strip covers
     * @param heightPx       strip height in pixels (at least 1)
     * @param sliceHeightPx  slice height in pixels (2 or 3 in practice; at least 1)
     * @param matches        match positions on the axis (may be null, unsorted, duplicates allowed)
     * @param bookmarks      bookmark positions on the axis (may be null)
     * @param dateChanges    axis positions of the local midnights, ascending, from the first one after the span
     *                       start (may be null); entry {@code k} starts local day {@code firstEpochDay + k + 1}
     * @param firstEpochDay  local epoch day of the span's first instant; empty for no day bands
     * @param visible        visible range on the axis; {@code null} for none
     * @return the immutable model
     */
    public static MinimapModel build(Span span, int heightPx, int sliceHeightPx, long[] matches, long[] bookmarks,
            long[] dateChanges, OptionalLong firstEpochDay, Span visible) {
        if (span == null) {
            throw new IllegalArgumentException("span cannot be null");
        }
        if (heightPx < 1 || sliceHeightPx < 1) {
            throw new IllegalArgumentException("heights must be positive");
        }
        int sliceCount = Math.max(1, heightPx / sliceHeightPx);

        int[] counts = new int[sliceCount];
        if (matches != null) {
            for (long position : matches) {
                if (span.contains(position)) {
                    counts[sliceOf(span, sliceCount, position)]++;
                }
            }
        }
        int max = Arrays.stream(counts).max().orElse(0);
        float[] alphas = new float[sliceCount];
        for (int i = 0; i < sliceCount; i++) {
            alphas[i] = counts[i] == 0
                    ? 0f
                    : MIN_ALPHA + (1f - MIN_ALPHA) * (float) Math.sqrt((double) counts[i] / max);
        }

        long[] changes = dateChanges == null
                ? new long[0]
                : Arrays.stream(dateChanges).filter(span::contains).toArray();
        BandUnit unit = null;
        List<Band> shaded = List.of();
        if (firstEpochDay.isPresent()) {
            unit = bandUnit(changes, firstEpochDay.getAsLong(), heightPx);
            if (unit != null) {
                shaded = shadedBands(span, heightPx, changes, firstEpochDay.getAsLong(), unit);
            }
        }

        int[] ticks = pixelsOf(span, heightPx, bookmarks);

        int from = 0;
        int to = heightPx;
        if (visible != null) {
            Band pixels = visiblePixels(span, heightPx, visible);
            from = pixels.top();
            to = pixels.bottom();
        }
        return new MinimapModel(span, heightPx, counts, alphas, unit, shaded, ticks, from, to);
    }

    /** Epoch nanoseconds of an instant (UTC). Valid for roughly ±292 years around 1970. */
    public static long nanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    /** The instant of epoch nanoseconds (UTC); the inverse of {@link #nanos(Instant)}. */
    public static Instant instantOf(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    /**
     * The pixel rows {@code [top, bottom)} a visible range covers in a strip of {@code heightPx} px: at least one
     * row, always inside the strip. What {@link #visibleFromY()} and {@link #visibleToY()} report.
     */
    public static Band visiblePixels(Span span, int heightPx, Span visible) {
        int from = Math.clamp(yOf(span, heightPx, visible.start()), 0, heightPx - 1);
        int to = Math.clamp(yOf(span, heightPx, visible.endExclusive()), from + 1, heightPx);
        return new Band(from, to);
    }

    /**
     * The axis position at pixel row {@code y} of a strip of {@code heightPx} px: the inverse of the
     * position-to-row mapping: the first position drawn at row {@code y}, {@code start + ceil(y * length / heightPx)}
     * (so a range centred there is drawn where the pointer is). {@code y} is clamped into the strip and the
     * result into the span, so the bottom row maps to the last unit.
     */
    public static long positionAt(Span span, int heightPx, int y) {
        if (heightPx < 1) {
            throw new IllegalArgumentException("heightPx must be positive");
        }
        long length = span.length();
        if (length == 0) {
            return span.start();
        }
        long offset = (long) Math.ceil(Math.clamp(y, 0, heightPx) * (double) length / heightPx);
        return Math.clamp(span.start() + offset, span.start(), span.endExclusive() - 1);
    }

    /**
     * Instants of local midnights strictly inside {@code (from, to)} in the given zone.
     * Empty when {@code to} is not after {@code from}.
     */
    public static List<Instant> dateChanges(Instant from, Instant to, ZoneId zone) {
        List<Instant> changes = new ArrayList<>();
        if (!to.isAfter(from)) {
            return changes;
        }
        LocalDate day = from.atZone(zone).toLocalDate().plusDays(1);
        Instant midnight = day.atStartOfDay(zone).toInstant();
        while (midnight.isBefore(to)) {
            if (midnight.isAfter(from)) {
                changes.add(midnight);
            }
            day = day.plusDays(1);
            midnight = day.atStartOfDay(zone).toInstant();
        }
        return changes;
    }

    public Span span() {
        return span;
    }

    public int heightPx() {
        return heightPx;
    }

    public int sliceCount() {
        return sliceCounts.length;
    }

    /** Match count per slice, index 0 at the top. */
    public int[] sliceCounts() {
        return sliceCounts.clone();
    }

    /** Opacity per slice in {@code [0.55, 1.0]}, {@code 0} for empty slices. */
    public float[] alphas() {
        return alphas.clone();
    }

    /** The unit of the day bands, or null when the strip has no bands (no boundary inside it, or too dense). */
    public BandUnit bandUnit() {
        return bandUnit;
    }

    /** The shaded bands in pixel rows, top to bottom; the strip's other bands are clear. */
    public List<Band> shadedBands() {
        return shadedBands;
    }

    /** Sorted, distinct pixel rows of the bookmark ticks. */
    public int[] bookmarkYs() {
        return bookmarkYs.clone();
    }

    /** First pixel row of the visible range (inclusive). */
    public int visibleFromY() {
        return visibleFromY;
    }

    /** Last pixel row of the visible range (exclusive); always at least one pixel below {@link #visibleFromY()}. */
    public int visibleToY() {
        return visibleToY;
    }

    /**
     * The finest unit whose band count ({@code boundaries + 1}) fits {@code heightPx / MIN_BAND_PX}; null when
     * none fits or the chosen unit has no boundary inside the strip.
     */
    private static BandUnit bandUnit(long[] changes, long firstEpochDay, int heightPx) {
        int maxBands = heightPx / MIN_BAND_PX;
        for (BandUnit unit : BandUnit.values()) {
            List<Integer> boundaries = boundariesOf(unit, changes.length, firstEpochDay);
            if (boundaries.size() + 1 <= maxBands) {
                return boundaries.isEmpty() ? null : unit;
            }
        }
        return null;
    }

    /** Indices {@code k} into the day changes whose day starts a unit of the given kind. */
    private static List<Integer> boundariesOf(BandUnit unit, int changeCount, long firstEpochDay) {
        List<Integer> boundaries = new ArrayList<>();
        for (int k = 0; k < changeCount; k++) {
            if (unit.startsUnit(firstEpochDay + k + 1)) {
                boundaries.add(k);
            }
        }
        return boundaries;
    }

    private static List<Band> shadedBands(Span span, int heightPx, long[] changes, long firstEpochDay,
            BandUnit unit) {
        List<Integer> boundaries = boundariesOf(unit, changes.length, firstEpochDay);
        List<Band> shaded = new ArrayList<>();
        int top = 0;
        long bandDay = firstEpochDay;
        for (int j = 0; j <= boundaries.size(); j++) {
            int bottom = j == boundaries.size()
                    ? heightPx
                    : Math.min(yOf(span, heightPx, changes[boundaries.get(j)]), heightPx - 1);
            if (Math.floorMod(unit.indexOf(bandDay), 2L) == 1 && bottom > top) {
                shaded.add(new Band(top, bottom));
            }
            if (j < boundaries.size()) {
                top = bottom;
                bandDay = firstEpochDay + boundaries.get(j) + 1;
            }
        }
        return List.copyOf(shaded);
    }

    private static int sliceOf(Span span, int sliceCount, long position) {
        return (int) Math.clamp((position - span.start()) * (double) sliceCount / span.length(), 0, sliceCount - 1);
    }

    private static int yOf(Span span, int heightPx, long position) {
        if (span.length() == 0) {
            return position < span.start() ? 0 : heightPx;
        }
        return (int) Math.clamp((position - span.start()) * (double) heightPx / span.length(), 0, heightPx);
    }

    private static int[] pixelsOf(Span span, int heightPx, long[] positions) {
        if (positions == null) {
            return new int[0];
        }
        return Arrays.stream(positions).filter(span::contains)
                .mapToInt(p -> Math.min(yOf(span, heightPx, p), heightPx - 1)).distinct().sorted().toArray();
    }
}
