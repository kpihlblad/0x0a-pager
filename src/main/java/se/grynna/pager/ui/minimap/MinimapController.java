package se.grynna.pager.ui.minimap;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Feeds a {@link Minimap}: decides what the strip shows from what the viewer reports, and keeps the cost of
 * a scroll down to updating the visible range.
 * <p>
 * The strip is always there (outline of the visible range, day bands, bookmark ticks); the amber match
 * density is added only while a search is active, i.e. when the viewer passes that search's snapshot (null
 * for none). Two axes. {@link #showRows} (log view): the strip spans the loaded segment by row; the rows of
 * the hits, bookmarks and date changes are found by {@link HitRows} on the background executor, never on the
 * EDT, and a result is applied only if it is still the latest request (a sequence number).
 * {@link #showTime} (Matches view, always a search): the strip spans the search's covered time window and
 * positions are times, computed directly. The expensive part is redone only when the snapshot (by identity:
 * the session caches it per version), the segment bounds, the bookmarks or the zone change; every call still
 * moves the visible range.
 * <p>
 * Row numbers are signed: a log is numbered around its jump target, so every row before it is negative.
 * <p>
 * Call from the EDT; {@code edt} runs the results back on it.
 */
@Slf4j
public final class MinimapController {

    /** Longer spans get no day bands: their midnights are too many to look up (20 years, so YEAR bands fit). */
    private static final Duration MAX_DAY_BANDS_SPAN = Duration.ofDays(366L * 20);

    private enum Axis {
        ROWS, TIME
    }

    /** What the last request was computed from. Snapshot and provider are compared by identity. */
    private record Key(Axis axis, SearchSession.Snapshot snapshot, Object provider, int start, int end,
            long bookmarksVersion, ZoneId zone) {

        boolean sameAs(Key other) {
            return other != null && axis == other.axis && snapshot == other.snapshot && provider == other.provider
                    && start == other.start && end == other.end && bookmarksVersion == other.bookmarksVersion
                    && zone.equals(other.zone);
        }

        /** Same axis, provider and segment: what is already drawn still fits the new request. */
        boolean sameSpanAs(Key other) {
            return other != null && axis == other.axis && provider == other.provider && start == other.start
                    && end == other.end;
        }
    }

    private final Minimap minimap;
    private final Executor background;
    private final Executor edt;
    private final BooleanSupplier enabled;
    private final Supplier<ZoneId> zone;
    private final Supplier<List<LoadedLogLine>> bookmarkEvents;
    private final LongSupplier bookmarksVersion;

    private final AtomicLong sequence = new AtomicLong();
    private Key last;

    public MinimapController(Minimap minimap, Executor background, Executor edt, BooleanSupplier enabled,
            Supplier<ZoneId> zone, Supplier<List<LoadedLogLine>> bookmarkEvents, LongSupplier bookmarksVersion) {
        this.minimap = minimap;
        this.background = background;
        this.edt = edt;
        this.enabled = enabled;
        this.zone = zone;
        this.bookmarkEvents = bookmarkEvents;
        this.bookmarksVersion = bookmarksVersion;
    }

    /** A single daemon thread for the hit-row work. */
    public static ExecutorService newBackgroundExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minimap-rows");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A view without a minimap (or the preference off): draw nothing and drop any work in flight. */
    public void hide() {
        sequence.incrementAndGet();
        last = null;
        minimap.clear();
    }

    /**
     * The log view: the strip spans the loaded segment {@code [startLine, endLineExclusive)} by row.
     *
     * @param snapshot         the active search's hits, or null without a search (no match density)
     * @param segmentFrom      time of the segment's first row, for the day bands (may be null)
     * @param segmentTo        time of the segment's last row (may be null)
     * @param peekLine         non-loading row lookup of {@code provider}
     * @param provider         identifies the log provider; a different one restarts the strip
     * @param visibleFirstRow  first row the viewport shows
     * @param visibleLastRow   last row the viewport shows (inclusive)
     */
    public void showRows(SearchSession.Snapshot snapshot, int startLine, int endLineExclusive, Instant segmentFrom,
            Instant segmentTo, IntFunction<Optional<LogLine>> peekLine, Object provider, int visibleFirstRow,
            int visibleLastRow) {
        if (!enabled.getAsBoolean() || endLineExclusive <= startLine) {
            hide();
            return;
        }
        ZoneId zoneId = zone.get();
        Key key = new Key(Axis.ROWS, snapshot, provider, startLine, endLineExclusive, bookmarksVersion.getAsLong(),
                zoneId);
        MinimapModel.Span visible = visibleFirstRow <= visibleLastRow
                ? new MinimapModel.Span(visibleFirstRow, (long) visibleLastRow + 1)
                : null;
        if (!key.sameAs(last)) {
            long mine = sequence.incrementAndGet();
            if (!key.sameSpanAs(last)) {
                minimap.clear();
            }
            last = key;
            List<SearchHit> hits = snapshot == null ? List.of() : snapshot.hits();
            List<LoadedLogLine> bookmarked = List.copyOf(bookmarkEvents.get());
            boolean capEarlier = snapshot != null && snapshot.truncatedEarlier();
            boolean capLater = snapshot != null && snapshot.truncatedLater();
            OptionalLong firstDay = firstEpochDay(segmentFrom, segmentTo, zoneId);
            background.execute(() -> computeRows(mine, hits, bookmarked, startLine, endLineExclusive, segmentFrom,
                    segmentTo, peekLine, zoneId, firstDay, capEarlier, capLater));
        }
        minimap.setVisibleRange(visible);
    }

    /**
     * The Matches view: the strip spans the search's covered time window and buckets matches by time. With no
     * snapshot there is no time axis, so nothing is drawn.
     *
     * @param snapshot    the search's hits, or null for none
     * @param visibleFrom splunk time of the first event the viewport shows (null for none)
     * @param visibleTo   splunk time of the last event the viewport shows
     */
    public void showTime(SearchSession.Snapshot snapshot, Instant visibleFrom, Instant visibleTo) {
        if (!enabled.getAsBoolean() || snapshot == null) {
            hide();
            return;
        }
        ZoneId zoneId = zone.get();
        Key key = new Key(Axis.TIME, snapshot, null, 0, 0, bookmarksVersion.getAsLong(), zoneId);
        if (!key.sameAs(last)) {
            sequence.incrementAndGet();
            last = key;
            if (!applyTime(snapshot, zoneId)) {
                minimap.clear();
                return;
            }
        }
        if (!minimap.hasData()) {
            return;
        }
        minimap.setVisibleRange(visibleFrom == null || visibleTo == null
                ? null
                : MinimapModel.Span.ofInstants(visibleFrom, visibleTo.plusNanos(1)));
    }

    private boolean applyTime(SearchSession.Snapshot snapshot, ZoneId zoneId) {
        List<SearchHit> hits = snapshot.hits();
        Instant from = snapshot.windowStart();
        Instant to = snapshot.windowEnd();
        for (SearchHit hit : hits) {
            Instant time = hit.splunkTime();
            from = from == null || time.isBefore(from) ? time : from;
            to = to == null || time.isAfter(to) ? time : to;
        }
        if (from == null || to == null) {
            return false;
        }
        try {
            MinimapModel.Span span = new MinimapModel.Span(MinimapModel.nanos(from), MinimapModel.nanos(to) + 1);
            long[] matches = hits.stream().mapToLong(hit -> MinimapModel.nanos(hit.splunkTime())).toArray();
            long[] bookmarks = bookmarkEvents.get().stream()
                    .mapToLong(line -> MinimapModel.nanos(line.getSplunkTime())).toArray();
            long[] dayChanges = dateChangeNanos(from, to, zoneId);
            minimap.setData(span, matches, bookmarks, dayChanges, firstEpochDay(from, to, zoneId),
                    snapshot.truncatedEarlier(), snapshot.truncatedLater());
            return true;
        } catch (ArithmeticException e) {
            log.debug("Minimap: window out of range ({} .. {})", from, to);
            return false;
        }
    }

    private void computeRows(long mine, List<SearchHit> hits, List<LoadedLogLine> bookmarked, int startLine,
            int endLineExclusive, Instant segmentFrom, Instant segmentTo, IntFunction<Optional<LogLine>> peekLine,
            ZoneId zoneId, OptionalLong firstDay, boolean capEarlier, boolean capLater) {
        try {
            BooleanSupplier stale = () -> sequence.get() != mine;
            // Only hits within the segment's time span can have a row; skipping the rest saves their probes.
            List<SearchHit> inSegment = segmentFrom == null || segmentTo == null
                    ? hits
                    : hits.stream().filter(hit -> !hit.splunkTime().isBefore(segmentFrom)
                            && !hit.splunkTime().isAfter(segmentTo)).toList();
            long[] matchRows = placed(HitRows.rowsOf(peekLine, startLine, endLineExclusive, inSegment, stale));
            List<SearchHit> bookmarkHits = bookmarked.stream().map(SearchHit::from).toList();
            long[] bookmarkRows = placed(HitRows.rowsOf(peekLine, startLine, endLineExclusive, bookmarkHits, stale));
            long[] dayChangeRows = dayChangeRows(segmentFrom, segmentTo, startLine, endLineExclusive, peekLine, zoneId,
                    stale);
            if (stale.getAsBoolean()) {
                return;
            }
            MinimapModel.Span span = new MinimapModel.Span(startLine, endLineExclusive);
            edt.execute(() -> {
                if (sequence.get() == mine) {
                    minimap.setData(span, matchRows, bookmarkRows, dayChangeRows, firstDay, capEarlier, capLater);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Minimap rows failed", e);
        }
    }

    /** The local epoch day of {@code from}, or empty when the span gets no day bands (unknown or too long). */
    private static OptionalLong firstEpochDay(Instant from, Instant to, ZoneId zoneId) {
        if (from == null || to == null || Duration.between(from, to).compareTo(MAX_DAY_BANDS_SPAN) > 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(from.atZone(zoneId).toLocalDate().toEpochDay());
    }

    private static long[] dayChangeRows(Instant from, Instant to, int startLine, int endLineExclusive,
            IntFunction<Optional<LogLine>> peekLine, ZoneId zoneId, BooleanSupplier stale) {
        if (from == null || to == null || Duration.between(from, to).compareTo(MAX_DAY_BANDS_SPAN) > 0) {
            return new long[0];
        }
        // Midnights are in time order and a row at or after a midnight is at or after the ones before it, so the
        // midnights without a loaded row are exactly the trailing ones: the list stays a prefix, indexed as in time.
        List<Integer> rows = new ArrayList<>();
        for (Instant midnight : MinimapModel.dateChanges(from, to, zoneId)) {
            int row = HitRows.firstRowAtOrAfter(peekLine, startLine, endLineExclusive, SearchHit.at(midnight), stale);
            if (row == HitRows.NOT_FOUND) {
                break;
            }
            rows.add(row);
        }
        return rows.stream().mapToLong(Integer::longValue).toArray();
    }

    private static long[] dateChangeNanos(Instant from, Instant to, ZoneId zoneId) {
        if (Duration.between(from, to).compareTo(MAX_DAY_BANDS_SPAN) > 0) {
            return new long[0];
        }
        return MinimapModel.dateChanges(from, to, zoneId).stream().mapToLong(MinimapModel::nanos).toArray();
    }

    private static long[] placed(int[] rows) {
        return Arrays.stream(rows).filter(row -> row != HitRows.NOT_FOUND).asLongStream().toArray();
    }
}
