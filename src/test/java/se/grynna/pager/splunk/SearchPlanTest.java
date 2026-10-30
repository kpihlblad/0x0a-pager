package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static se.grynna.pager.splunk.SearchSession.Side.EARLIER;
import static se.grynna.pager.splunk.SearchSession.Side.LATER;

class SearchPlanTest {
    private static final Instant T = Instant.parse("2017-03-30T10:00:00.123456789Z");

    private SearchPlan plan() {
        return new SearchPlan(T, T.minusSeconds(100_000), T.plusSeconds(100_000), 10_000);
    }

    @Test
    void doublesRadiiWithExactAdjacentHalfOpenChunksOnBothSides() {
        SearchPlan plan = plan();
        long[] radii = {60, 120, 240, 480};
        long previous = 0;
        for (long radius : radii) {
            for (var side : SearchSession.Side.values()) {
                var chunk = plan.next(side).orElseThrow();
                assertThat(plan.next(side)).contains(chunk);
                assertThat(chunk.start()).isEqualTo(T.plusSeconds(side == LATER ? previous : -radius));
                assertThat(chunk.end()).isEqualTo(T.plusSeconds(side == LATER ? radius : -previous));
                plan.completed(chunk, 100, 9000);
            }
            previous = radius;
        }
    }

    @Test
    void sparseChunksGrowRadiusEightfoldAndThresholdIsStrict() {
        SearchPlan sparse = plan();
        sparse.completed(sparse.next(LATER).orElseThrow(), 99, 9901);
        assertThat(sparse.next(LATER).orElseThrow().end()).isEqualTo(T.plusSeconds(480));
        SearchPlan normal = plan();
        normal.completed(normal.next(LATER).orElseThrow(), 100, 9900);
        assertThat(normal.next(LATER).orElseThrow().end()).isEqualTo(T.plusSeconds(120));
    }

    @Test
    void takesRestBelowFourTimesNextRadiusButNotAtThreshold() {
        SearchPlan small = new SearchPlan(T, T, T.plusSeconds(239).plusNanos(999_999_999), 100);
        var chunk = small.next(LATER).orElseThrow();
        assertThat(chunk.end()).isEqualTo(T.plusSeconds(239).plusNanos(999_999_999));
        small.completed(chunk, 0, 100);
        assertThat(small.next(LATER)).isEmpty();
        assertThat(small.state(LATER).exhausted()).isTrue();
        SearchPlan exact = new SearchPlan(T, T, T.plusSeconds(240), 100);
        assertThat(exact.next(LATER).orElseThrow().end()).isEqualTo(T.plusSeconds(60));
        SearchPlan second = new SearchPlan(T, T, T.plusSeconds(500), 100);
        second.completed(second.next(LATER).orElseThrow(), 1, 99);
        assertThat(second.next(LATER).orElseThrow().end()).isEqualTo(T.plusSeconds(500));
    }

    @Test
    void clampsOriginAndWindowWithoutRoundingNearEdges() {
        SearchPlan low = new SearchPlan(T.minusSeconds(100), T, T.plusNanos(12), 10);
        assertThat(low.origin()).isEqualTo(T);
        assertThat(low.next(EARLIER)).isEmpty();
        var chunk = low.next(LATER).orElseThrow();
        assertThat(chunk.start()).isEqualTo(T);
        assertThat(chunk.end()).isEqualTo(T.plusNanos(12));
        SearchPlan high = new SearchPlan(T.plusSeconds(100), T, T.plusNanos(12), 10);
        assertThat(high.next(LATER)).isEmpty();
        assertThat(high.next(EARLIER).orElseThrow()).isEqualTo(new SearchPlan.Chunk(EARLIER, T, T.plusNanos(12)));
        SearchPlan near = new SearchPlan(T.plusNanos(1), T, T.plusSeconds(1000), 10);
        assertThat(near.next(EARLIER).orElseThrow().start()).isEqualTo(T);
        assertThat(near.next(EARLIER).orElseThrow().end()).isEqualTo(T.plusNanos(1));
        SearchPlan empty = new SearchPlan(T, T, T, 10);
        assertThat(empty.next(EARLIER)).isEmpty();
        assertThat(empty.next(LATER)).isEmpty();
    }

    @Test
    void firstChunkHalvesActualWidthToOneSecondThenGivesUpOnOnlyThatSide() {
        for (var side : SearchSession.Side.values()) {
            SearchPlan plan = plan();
            var chunk = plan.next(side).orElseThrow();
            Duration width = Duration.ofSeconds(60);
            while (width.compareTo(Duration.ofSeconds(1)) > 0) {
                width = width.dividedBy(2);
                if (width.compareTo(Duration.ofSeconds(1)) < 0) width = Duration.ofSeconds(1);
                chunk = plan.overflowed(chunk).orElseThrow();
                assertThat(Duration.between(chunk.start(), chunk.end())).isEqualTo(width);
                assertThat(side == LATER ? chunk.start() : chunk.end()).isEqualTo(T);
            }
            assertThat(plan.overflowed(chunk)).isEmpty();
            assertThat(plan.state(side)).isEqualTo(new SearchPlan.SideState(true, true, true));
            assertThat(plan.next(side)).isEmpty();
            assertThat(plan.next(side == LATER ? EARLIER : LATER)).isPresent();
        }
    }

    @Test
    void fittingHalvedFirstChunkEndsSideTruncatedAtItsFarEdge() {
        SearchPlan plan = plan();
        var first = plan.next(EARLIER).orElseThrow();
        assertThat(first).isEqualTo(new SearchPlan.Chunk(EARLIER, T.minusSeconds(60), T));
        var retry = plan.overflowed(first).orElseThrow();
        assertThat(retry).isEqualTo(new SearchPlan.Chunk(EARLIER, T.minusSeconds(30), T));
        plan.completed(retry, 200, 9800);
        assertThat(plan.state(EARLIER)).isEqualTo(new SearchPlan.SideState(true, true, false));
        assertThat(plan.next(EARLIER)).isEmpty();
        assertThat(plan.next(LATER)).isPresent();
    }

    @Test
    void nonFirstOverflowHalvesFromTheSameCursorUntilTheRetryFitsAndThenEndsTheSide() {
        SearchPlan plan = plan();
        plan.completed(plan.next(LATER).orElseThrow(), 100, 9900);
        var second = plan.next(LATER).orElseThrow();
        assertThat(second).isEqualTo(new SearchPlan.Chunk(LATER, T.plusSeconds(60), T.plusSeconds(120)));
        var retry = plan.overflowed(second).orElseThrow();
        assertThat(retry).isEqualTo(new SearchPlan.Chunk(LATER, T.plusSeconds(60), T.plusSeconds(90)));
        assertThat(plan.state(LATER).truncated()).isFalse();
        var quarter = plan.overflowed(retry).orElseThrow();
        assertThat(quarter).isEqualTo(new SearchPlan.Chunk(LATER, T.plusSeconds(60), T.plusSeconds(75)));
        plan.completed(quarter, 2, 9000);
        assertThat(plan.state(LATER)).isEqualTo(new SearchPlan.SideState(true, true, false));
        assertThat(plan.next(LATER)).isEmpty();
        assertThat(plan.next(EARLIER)).isPresent();
    }

    @Test
    void oneSecondOverflowOnNonFirstChunkEndsTruncatedWithoutGivingUp() {
        SearchPlan plan = plan();
        plan.completed(plan.next(EARLIER).orElseThrow(), 300, 9700);
        var chunk = plan.next(EARLIER).orElseThrow();
        assertThat(chunk).isEqualTo(new SearchPlan.Chunk(EARLIER, T.minusSeconds(120), T.minusSeconds(60)));
        while (Duration.between(chunk.start(), chunk.end()).compareTo(Duration.ofSeconds(1)) > 0) {
            chunk = plan.overflowed(chunk).orElseThrow();
            assertThat(chunk.end()).isEqualTo(T.minusSeconds(60));
        }
        assertThat(Duration.between(chunk.start(), chunk.end())).isEqualTo(Duration.ofSeconds(1));
        assertThat(chunk.start()).isEqualTo(T.minusSeconds(61));
        assertThat(plan.overflowed(chunk)).isEmpty();
        assertThat(plan.state(EARLIER)).isEqualTo(new SearchPlan.SideState(true, true, false));
    }

    @Test
    void halvingTruncatesToWholeNanosecondsAndKeepsTheWindowExact() {
        Instant latest = T.plusSeconds(7).plusNanos(1);
        SearchPlan plan = new SearchPlan(T, T, latest, 1000);
        var whole = plan.next(LATER).orElseThrow();
        assertThat(whole).isEqualTo(new SearchPlan.Chunk(LATER, T, latest));
        var retry = plan.overflowed(whole).orElseThrow();
        assertThat(retry.start()).isEqualTo(T);
        assertThat(Duration.between(retry.start(), retry.end())).isEqualTo(Duration.ofNanos(3_500_000_000L));
        assertThat(retry.end()).isEqualTo(T.plusNanos(3_500_000_000L));
        assertThat(retry.end()).isBefore(latest);
    }

    /**
     * A halved retry is always narrower than the remaining span, so it can never reach
     * the window edge; the "reached the edge" branch is checked here through the
     * non-overflow path instead.
     */
    @Test
    void aChunkReachingTheWindowEdgeEndsExhaustedAndNotTruncated() {
        SearchPlan plan = new SearchPlan(T, T, T.plusSeconds(60), 1000);
        var chunk = plan.next(LATER).orElseThrow();
        assertThat(chunk).isEqualTo(new SearchPlan.Chunk(LATER, T, T.plusSeconds(60)));
        plan.completed(chunk, 5, 995);
        assertThat(plan.state(LATER)).isEqualTo(new SearchPlan.SideState(true, false, false));
        assertThat(plan.next(LATER)).isEmpty();
    }

    @Test
    void exhaustedBudgetTruncatesUnlessWindowEdgeWasReached() {
        SearchPlan plan = plan();
        plan.completed(plan.next(LATER).orElseThrow(), 10_000, 0);
        assertThat(plan.state(LATER)).isEqualTo(new SearchPlan.SideState(true, true, false));
        SearchPlan tiny = new SearchPlan(T, T, T.plusSeconds(1), 1);
        tiny.completed(tiny.next(LATER).orElseThrow(), 1, 0);
        assertThat(tiny.state(LATER)).isEqualTo(new SearchPlan.SideState(true, false, false));
    }
}
