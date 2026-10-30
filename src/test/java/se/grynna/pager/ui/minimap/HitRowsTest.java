package se.grynna.pager.ui.minimap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class HitRowsTest {

    private static final Instant T1 = Instant.parse("2023-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2023-01-01T10:01:00Z");
    private static final Instant T3 = Instant.parse("2023-01-01T10:02:00Z");
    private static final Instant T4 = Instant.parse("2023-01-01T10:03:00Z");

    /** A fake segment: index = row, null = not loaded. */
    private static final class Rows {
        private final List<LogLine> rows = new ArrayList<>();
        private final AtomicInteger peeks = new AtomicInteger();
        private final int firstRow;

        Rows() {
            this(0);
        }

        /** A segment whose first row is {@code firstRow}: a log is numbered around its jump target, so negative. */
        Rows(int firstRow) {
            this.firstRow = firstRow;
        }

        Rows event(String cb, Instant time, int lines) {
            for (int i = 0; i < lines; i++) {
                rows.add(line(firstRow + rows.size(), cb, time, i > 0));
            }
            return this;
        }

        Rows unloaded(int count) {
            for (int i = 0; i < count; i++) {
                rows.add(null);
            }
            return this;
        }

        Rows loadedContinuation(String cb, Instant time) {
            rows.add(line(firstRow + rows.size(), cb, time, true));
            return this;
        }

        Optional<LogLine> peek(int row) {
            peeks.incrementAndGet();
            int index = row - firstRow;
            if (index < 0 || index >= rows.size()) {
                return Optional.empty();
            }
            return Optional.ofNullable(rows.get(index));
        }

        SearchHit hit(int row) {
            return SearchHit.from((LoadedLogLine) rows.get(row - firstRow));
        }

        int size() {
            return rows.size();
        }

        private static LoadedLogLine line(int row, String cb, Instant time, boolean continuation) {
            return new LoadedLogLine(LoadedLogLine.MsgType.RAW, "seg", row, cb, "bkt", time, "raw", time, time,
                    "msg", continuation, null);
        }
    }

    private static final BooleanSupplier NEVER = () -> false;

    @Test
    @DisplayName("firstRowAtOrAfter finds the first loaded row at or after a time, skipping unloaded rows")
    void firstRowAtOrAfterATime() {
        Rows segment = new Rows().event("1:1", T1, 2).unloaded(2).event("1:3", T3, 1).event("1:4", T4, 1);

        assertThat(HitRows.firstRowAtOrAfter(segment::peek, 0, segment.size(), SearchHit.at(T1), NEVER)).isZero();
        assertThat(HitRows.firstRowAtOrAfter(segment::peek, 0, segment.size(), SearchHit.at(T2), NEVER)).isEqualTo(4);
        assertThat(HitRows.firstRowAtOrAfter(segment::peek, 0, segment.size(), SearchHit.at(T4), NEVER)).isEqualTo(5);
        assertThat(HitRows.firstRowAtOrAfter(segment::peek, 0, segment.size(), SearchHit.at(T4.plusSeconds(1)), NEVER))
                .isEqualTo(HitRows.NOT_FOUND);
    }

    @Test
    @DisplayName("finds the first row of each event, including multi-row events with continuation rows")
    void multiRowEvents() {
        Rows segment = new Rows().event("1:1", T1, 2).event("1:2", T2, 1).event("1:3", T3, 3).event("1:4", T4, 1);

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(),
                List.of(segment.hit(2), segment.hit(3), segment.hit(0)), NEVER);

        assertThat(rows).containsExactly(2, 3, 0);
    }

    @Test
    @DisplayName("equal timestamps are resolved by identity")
    void equalTimestamps() {
        Rows segment = new Rows().event("1:5", T2, 1).event("1:6", T2, 2).event("1:7", T2, 1).event("1:8", T3, 1);

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(),
                List.of(segment.hit(2), segment.hit(0), segment.hit(3)), NEVER);

        assertThat(rows).containsExactly(1, 0, 3);
    }

    @Test
    @DisplayName("unloaded rows are skipped, never loaded; hits whose event is not loaded are not found")
    void unloadedHoles() {
        Rows segment = new Rows().event("1:1", T1, 1).unloaded(4).event("1:2", T2, 1).unloaded(3)
                .event("1:3", T3, 2).unloaded(2).event("1:4", T4, 1);
        SearchHit notLoaded = new SearchHit(T2, T2, "bkt", "1:9");

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(),
                List.of(segment.hit(5), segment.hit(0), segment.hit(13), notLoaded), NEVER);

        assertThat(rows).containsExactly(5, 0, 13, HitRows.NOT_FOUND);
    }

    @Test
    @DisplayName("finds events among holes when the surrounding rows are loaded")
    void holesAroundTarget() {
        Rows segment = new Rows().unloaded(3).event("1:1", T1, 1).unloaded(20).event("1:2", T2, 1).unloaded(3);

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(), List.of(segment.hit(3), segment.hit(24)), NEVER);

        assertThat(rows).containsExactly(3, 24);
    }

    @Test
    @DisplayName("hits outside the segment bounds are not found and the peek stays inside the segment")
    void hitsOutsideSegment() {
        Rows segment = new Rows().event("1:1", T1, 1).event("1:2", T2, 1).event("1:3", T3, 1).event("1:4", T4, 1);
        AtomicInteger outOfRange = new AtomicInteger();

        int[] rows = HitRows.rowsOf(row -> {
            if (row < 1 || row >= 3) {
                outOfRange.incrementAndGet();
            }
            return segment.peek(row);
        }, 1, 3, List.of(segment.hit(0), segment.hit(3), segment.hit(2), segment.hit(1)), NEVER);

        assertThat(rows).containsExactly(HitRows.NOT_FOUND, HitRows.NOT_FOUND, 2, 1);
        assertThat(outOfRange.get()).as("peeks outside [1, 3)").isZero();
    }

    @Test
    @DisplayName("an event whose first row is unloaded resolves to its first loaded continuation row")
    void unloadedFirstRowOfEvent() {
        Rows segment = new Rows().unloaded(1).loadedContinuation("1:2", T2).event("1:3", T3, 1);

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(), List.of(segment.hit(1)), NEVER);

        assertThat(rows).containsExactly(1);
    }

    @Test
    @DisplayName("hits without identity match on both times")
    void hitWithoutIdentity() {
        Rows segment = new Rows().event("1:1", T1, 1).event("1:2", T2, 2).event("1:3", T3, 1);

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(), List.of(SearchHit.at(T2), SearchHit.at(T4)),
                NEVER);

        assertThat(rows).containsExactly(1, HitRows.NOT_FOUND);
    }

    @Test
    @DisplayName("empty input and empty range give empty or not-found results")
    void emptyInputs() {
        Rows segment = new Rows().event("1:1", T1, 1);

        assertThat(HitRows.rowsOf(segment::peek, 0, segment.size(), List.of(), NEVER)).isEmpty();
        assertThat(HitRows.rowsOf(segment::peek, 0, 0, List.of(segment.hit(0)), NEVER))
                .containsExactly(HitRows.NOT_FOUND);
    }

    @Test
    @DisplayName("cancellation stops the search: remaining hits are not found and no more peeks happen")
    void cancellation() {
        Rows segment = new Rows().event("1:1", T1, 1).event("1:2", T2, 1).event("1:3", T3, 1);
        AtomicInteger calls = new AtomicInteger();
        BooleanSupplier cancelledMidway = () -> calls.incrementAndGet() > 1;

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(),
                List.of(segment.hit(0), segment.hit(1), segment.hit(2)), cancelledMidway);

        assertThat(rows).containsOnly(HitRows.NOT_FOUND);
        assertThat(calls.get()).isLessThan(10);
    }

    @Test
    @DisplayName("cancelled before start: nothing is peeked")
    void cancelledImmediately() {
        Rows segment = new Rows().event("1:1", T1, 1);

        int[] rows = HitRows.rowsOf(segment::peek, 0, segment.size(), List.of(segment.hit(0)), () -> true);

        assertThat(rows).containsExactly(HitRows.NOT_FOUND);
        assertThat(segment.peeks.get()).isZero();
    }

    @Test
    @DisplayName("rows before the jump target are negative and are found: -1 is a row, not 'not found'")
    void negativeRows() {
        Rows segment = new Rows(-4).event("1:1", T1, 2).event("1:2", T2, 1).event("1:3", T3, 2).event("1:4", T4, 1);

        int[] rows = HitRows.rowsOf(segment::peek, -4, segment.size() - 4,
                List.of(segment.hit(-4), segment.hit(-2), segment.hit(-1), segment.hit(0), segment.hit(1)), NEVER);

        assertThat(rows).containsExactly(-4, -2, -1, -1, 1);
        assertThat(HitRows.firstRowAtOrAfter(segment::peek, -4, segment.size() - 4, SearchHit.at(T3), NEVER))
                .isEqualTo(-1);
        assertThat(HitRows.firstRowAtOrAfter(segment::peek, -4, segment.size() - 4, SearchHit.at(T4.plusSeconds(1)),
                NEVER)).isEqualTo(HitRows.NOT_FOUND);
    }
}
