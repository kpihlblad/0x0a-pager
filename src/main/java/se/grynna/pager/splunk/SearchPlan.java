package se.grynna.pager.splunk;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Optional;

import static se.grynna.pager.splunk.SearchSession.Side;

/** Pure, executor-driven outward search plan. Callers serialize feedback and next calls. */
public final class SearchPlan {
    static final Duration INITIAL_RADIUS = Duration.ofMinutes(1);
    static final Duration MIN_CHUNK = Duration.ofSeconds(1);
    static final int NORMAL_GROWTH = 2;
    static final int SPARSE_GROWTH = 8;
    static final int SPARSE_PERCENT = 1;
    static final int TAKE_REST_MULTIPLIER = 4;

    public record Chunk(Side side, Instant start, Instant end) {}

    public record SideState(boolean exhausted, boolean truncated, boolean gaveUpBelowOneSecond) {}

    private static final class Progress {
        Instant cursor;
        final Instant edge;
        Duration radius = INITIAL_RADIUS;
        Duration coveredRadius = Duration.ZERO;
        Chunk pending;
        boolean first = true;
        boolean hasOverflowed;
        boolean exhausted;
        boolean truncated;
        boolean gaveUp;

        Progress(Instant origin, Instant edge) {
            cursor = origin;
            this.edge = edge;
            exhausted = origin.compareTo(edge) == 0;
        }
    }

    private final EnumMap<Side, Progress> sides = new EnumMap<>(Side.class);
    private final int budget;
    private final Instant origin;

    public SearchPlan(Instant origin, Instant earliest, Instant latest, int budget) {
        if (earliest.isAfter(latest) || budget <= 0) {
            throw new IllegalArgumentException("Ordered window and positive budget required");
        }
        this.origin = origin.isBefore(earliest) ? earliest : origin.isAfter(latest) ? latest : origin;
        this.budget = budget;
        sides.put(Side.EARLIER, new Progress(this.origin, earliest));
        sides.put(Side.LATER, new Progress(this.origin, latest));
    }

    public Instant origin() {
        return origin;
    }

    /** Repeated calls before feedback return the same chunk. */
    public Optional<Chunk> next(Side side) {
        Progress p = sides.get(side);
        if (p.exhausted) return Optional.empty();
        if (p.pending == null) {
            Duration remaining = Duration.between(p.cursor, p.edge).abs();
            Duration width = p.radius.minus(p.coveredRadius);
            if (remaining.compareTo(p.radius.multipliedBy(TAKE_REST_MULTIPLIER)) < 0) {
                width = remaining;
            }
            p.pending = chunk(side, p.cursor, width);
        }
        return Optional.of(p.pending);
    }

    public void completed(Chunk chunk, int matches, int remainingBudget) {
        Progress p = pending(chunk);
        p.cursor = chunk.side() == Side.LATER ? chunk.end() : chunk.start();
        p.first = false;
        p.pending = null;
        p.exhausted = p.cursor.compareTo(p.edge) == 0;
        p.coveredRadius = Duration.between(origin, p.cursor).abs();
        if (p.hasOverflowed) {
            // A fitting retry ends the side: no re-growth, so no grow/halve ping-pong.
            p.truncated = !p.exhausted;
            p.exhausted = true;
            return;
        }
        int growth = (long) matches * 100 < (long) budget * SPARSE_PERCENT ? SPARSE_GROWTH : NORMAL_GROWTH;
        // Bound arithmetic by the finite window, including extreme Instant windows.
        Duration total = Duration.between(origin, p.edge).abs();
        p.radius = p.coveredRadius.multipliedBy(growth).compareTo(total) > 0
                ? total
                : p.coveredRadius.multipliedBy(growth);
        if (remainingBudget <= 0 && !p.exhausted) {
            p.exhausted = true;
            p.truncated = true;
        }
    }

    /**
     * Halve any overflowing chunk and retry it adjacently, down to {@link #MIN_CHUNK}.
     * If even a one-second chunk overflows the side ends truncated; only on the
     * side's first chunk does that flag a give-up ("narrow the search").
     */
    public Optional<Chunk> overflowed(Chunk chunk) {
        Progress p = pending(chunk);
        Duration width = Duration.between(chunk.start(), chunk.end());
        if (width.compareTo(MIN_CHUNK) > 0) {
            Duration half = width.dividedBy(2);
            if (half.compareTo(MIN_CHUNK) < 0) half = MIN_CHUNK;
            p.hasOverflowed = true;
            p.radius = half;
            p.pending = chunk(chunk.side(), p.cursor, half);
            return Optional.of(p.pending);
        }
        p.pending = null;
        p.exhausted = true;
        p.truncated = true;
        p.gaveUp = p.first;
        return Optional.empty();
    }

    public SideState state(Side side) {
        Progress p = sides.get(side);
        return new SideState(p.exhausted, p.truncated, p.gaveUp);
    }

    private Progress pending(Chunk chunk) {
        Progress p = sides.get(chunk.side());
        if (!chunk.equals(p.pending) || p.exhausted) {
            throw new IllegalArgumentException("Not the pending chunk: " + chunk);
        }
        return p;
    }

    private static Chunk chunk(Side side, Instant cursor, Duration width) {
        return side == Side.LATER
                ? new Chunk(side, cursor, cursor.plus(width))
                : new Chunk(side, cursor.minus(width), cursor);
    }
}
