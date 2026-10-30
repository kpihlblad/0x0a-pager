package se.grynna.pager.splunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.testutils.MutableClock;
import se.grynna.pager.ui.BoundaryRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.JSON;

class LoadingStrategyTest {

    private static final Instant DAY = Instant.parse("2025-05-10T00:00:00Z");
    private static final Instant NEXT_DAY = DAY.plus(1, ChronoUnit.DAYS);

    @Test
    void estimateTimeRangeFallsBackWhenSegmentHasZeroDuration() {
        Clock clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        SearchBoundaries searchBoundaries = new SearchBoundaries(clock);
        LoadingStrategy strategy = new LoadingStrategy(clock, searchBoundaries);

        // Set boundaries so we don't hit boundary logic
        searchBoundaries.setMinBoundary(Instant.parse("2020-01-01T00:00:00Z"));
        searchBoundaries.setMaxBoundary(Instant.parse("2030-01-01T00:00:00Z"));

        // Record a previous load so lastLoadCount != 0

        var start = Instant.parse("2024-12-31T00:00:00Z");
        var end = Instant.parse("2024-12-31T00:01:00Z");
        var loadedLines = new LoadedLogLines(start, end, fakeLoadedLines(10, start, end));

        strategy.recordLoadResult(0, loadedLines, LoadDecision.load(null, 0, start, end, "seed"));

        // Segment with non-zero size but zero duration => getLogRate() returns 0.
        LogSegment segment = new LogSegment(Instant.parse("2024-12-31T12:00:00Z"),
                Instant.parse("2024-12-31T12:00:00Z"), 0, 100);

        LoadDecision decision = strategy.evaluateLineRequest(-1, segment, true);
        assertTrue(decision.shouldLoad());
        assertTrue(decision.end().isAfter(decision.start()));
    }

    @Test
    void testInitEmptyBinarySearcAtNowBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T17:00:00Z"), ZoneOffset.UTC);
        SearchBoundaries searchBoundaries = new SearchBoundaries(clock);
        LoadingStrategy strategy = new LoadingStrategy(clock, searchBoundaries);

        // Set boundaries so we don't hit boundary logic
        searchBoundaries.setMinBoundary(Instant.parse("2020-01-01T00:00:00Z"));
        searchBoundaries.setMaxBoundary(Instant.parse("2030-01-01T00:00:00Z"));

        // Record a previous load so lastLoadCount != 0

        var start = Instant.parse("2025-01-01T16:00:00Z");
        var end = Instant.parse("2025-01-01T16:45:00Z");
        var loadedLines = new LoadedLogLines(start, end, fakeLoadedLines(0, start, end));

        System.out.println(">>> recording load result with zero loaded lines (direction 1");
        strategy.recordLoadResult(0, loadedLines, LoadDecision.load(null, 1, start, end, "seed"));

        // Segment with non-zero size but zero duration => getLogRate() returns 0.
        LogSegment segment = new LogSegment(start, end, 0, 10);

        assertThat(strategy.getDownState()).isInstanceOf(DirectionLater.BinarySearchLater.class);

        System.out.println(">>> evaluating line request 20, binary search later state");
        LoadDecision decision = strategy.evaluateLineRequest(20, segment, true);

        assertThat(decision.shouldLoad()).isTrue();

        System.out.println("Decision: " + decision);
        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());

        // assert range is up to NOW
        assertThat(decision.end()).isEqualTo(searchBoundaries.getNowBoundary());

        // record another empty load result, simulating that we reached NOW
        start = decision.start();
        end = decision.end();
        loadedLines = new LoadedLogLines(start, end, fakeLoadedLines(0, start, end));
        strategy.recordLoadResult(0, loadedLines, LoadDecision.load(null, 1, start, end, "seed"));
        segment.add(decision, loadedLines);

        System.out.println(">>> evaluating line request 20, atNow state");
        decision = strategy.evaluateLineRequest(20, segment, true);
        System.out.println("Decision: " + decision);
        assertFalse(decision.shouldLoad());

        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());
        System.out.println(">>> TIME IS AN UNFAITHFUL SERVANT, advance the test clock by 1 minute.");
        clock.fastForward(Duration.ofMinutes(1));
        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());

        System.out.println(">>> evaluating line request 20, atNow state");
        decision = strategy.evaluateLineRequest(20, segment, true);
        System.out.println("Decision: " + decision);
        assertTrue(decision.shouldLoad());

        System.out.println("Decision: " + decision);
        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());

        // assert range is up to NOW
        assertThat(decision.end()).isEqualTo(searchBoundaries.getNowBoundary());
    }

    @Test
    void testInitEmptAtNowBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T17:00:00Z"), ZoneOffset.UTC);
        SearchBoundaries searchBoundaries = new SearchBoundaries(clock);
        LoadingStrategy strategy = new LoadingStrategy(clock, searchBoundaries);

        // Set boundaries so we don't hit boundary logic
        searchBoundaries.setMinBoundary(Instant.parse("2020-01-01T00:00:00Z"));
        searchBoundaries.setMaxBoundary(Instant.parse("2030-01-01T00:00:00Z"));

        // Record a previous load so lastLoadCount != 0

        var start = Instant.parse("2025-01-01T16:54:00Z");

        // Segment with non-zero size
        LogSegment segment = new LogSegment(start.minus(Duration.ofMinutes(5)), start, 0, 10);

        assertThat(strategy.getDownState()).isInstanceOf(DirectionLater.Init.class);

        var decision = strategy.evaluateLineRequest(20, segment, true);
        assertThat(decision.shouldLoad()).isTrue();

        var loadedLines = new LoadedLogLines(decision.start(), decision.end(),
                fakeLoadedLines(0, decision.start(), decision.end()));
        segment.add(decision, loadedLines);
        strategy.recordLoadResult(20, loadedLines, decision);

        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());
        assertThat(strategy.getDownState()).isInstanceOf(DirectionLater.AtNowBoundary.class);

        // try again - no clock tick
        System.out.println(">>> evaluating line request 20, atNow state");
        decision = strategy.evaluateLineRequest(20, segment, true);
        System.out.println("Decision: " + decision);
        assertFalse(decision.shouldLoad());

        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());
        System.out.println(">>> TIME IS AN UNFAITHFUL SERVANT, advance the test clock by 1 minute.");
        clock.fastForward(Duration.ofMinutes(1));
        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());

        System.out.println(">>> evaluating line request 20, atNow state");
        decision = strategy.evaluateLineRequest(20, segment, true);
        System.out.println("Decision: " + decision);
        assertTrue(decision.shouldLoad());

        System.out.println("Decision: " + decision);
        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());
        // assert range is up to NOW
        assertThat(decision.end()).isEqualTo(searchBoundaries.getNowBoundary());

        loadedLines = new LoadedLogLines(decision.start(), decision.end(),
                fakeLoadedLines(0, decision.start(), decision.end()));
        System.out.println(">>> segment -record load empty read 2");
        segment.add(decision, loadedLines);
        System.out.println(">>> segment -record load empty read 2");
        strategy.recordLoadResult(20, loadedLines, decision);

        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());
        assertThat(strategy.getDownState()).isInstanceOf(DirectionLater.AtNowBoundary.class);

        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());
        System.out.println(">>> TIME IS AN UNFAITHFUL SERVANT, advance the test clock by 1 minute.");
        clock.fastForward(Duration.ofMinutes(1));
        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());

        System.out.println(">>> evaluating line request 20, atNow state");
        decision = strategy.evaluateLineRequest(20, segment, true);
        System.out.println("Decision: " + decision);
        assertTrue(decision.shouldLoad());

        System.out.println("Decision: " + decision);
        System.out.println("Now boundary: " + searchBoundaries.getNowBoundary());
        // assert range is up to NOW
        assertThat(decision.end()).isEqualTo(searchBoundaries.getNowBoundary());
    }

    @Test
    void earlierLoadAtSegmentStartPromptsInsteadOfThrowing() {
        Instant boundary = Instant.parse("2026-06-17T00:00:00Z");
        Instant segmentEnd = Instant.parse("2026-09-22T22:12:00Z");
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T22:17:00Z"), ZoneOffset.UTC);
        SearchBoundaries bounds = new SearchBoundaries(clock);
        bounds.setMinBoundary(Instant.parse("2026-06-01T00:00:00Z"));
        bounds.setMaxBoundary(segmentEnd);
        LoadingStrategy strategy = new LoadingStrategy(clock, bounds);

        Instant seedStart = Instant.parse("2026-06-16T00:00:00Z");
        Instant seedEnd = Instant.parse("2026-06-16T12:00:00Z");
        strategy.recordLoadResult(-10, new LoadedLogLines(seedStart, seedEnd, fakeLoadedLines(10, seedStart, seedEnd)),
                LoadDecision.load(null, -1, seedStart, seedEnd, "seed"));

        bounds.setMinBoundary(boundary);
        LogSegment segment = new LogSegment(boundary, segmentEnd, -230, 231);
        AtomicReference<BoundaryRequest> prompted = new AtomicReference<>();
        strategy.setBoundaryExpansionListener(prompted::set);

        LoadDecision decision = strategy.evaluateLineRequest(-231, segment, true);

        assertThat(decision.shouldLoad()).isFalse();
        assertThat(decision.boundaryHit()).isEqualTo(BoundaryType.BEFORE);
        assertThat(prompted.get()).isNotNull();
        assertThat(prompted.get().type()).isEqualTo(BoundaryType.BEFORE);
        assertThat(prompted.get().suggestedBoundary()).isBefore(boundary);

        prompted.set(null);
        LoadDecision again = strategy.evaluateLineRequest(-232, segment, true);
        assertThat(again.shouldLoad()).isFalse();
        assertThat(again.boundaryHit()).isEqualTo(BoundaryType.BEFORE);
        assertThat(prompted.get()).isNull();
    }

    @Test
    void laterLoadAtSegmentEndPromptsInsteadOfThrowing() {
        Instant segmentStart = Instant.parse("2026-06-17T00:00:00Z");
        Instant boundary = Instant.parse("2026-09-22T00:00:00Z");
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
        SearchBoundaries bounds = new SearchBoundaries(clock);
        bounds.setMinBoundary(segmentStart);
        bounds.setMaxBoundary(Instant.parse("2026-09-30T00:00:00Z"));
        LoadingStrategy strategy = new LoadingStrategy(clock, bounds);

        Instant seedStart = boundary.minus(Duration.ofHours(2));
        Instant seedEnd = boundary.minus(Duration.ofHours(1));
        strategy.recordLoadResult(1, new LoadedLogLines(seedStart, seedEnd, fakeLoadedLines(10, seedStart, seedEnd)),
                LoadDecision.load(null, 1, seedStart, seedEnd, "seed"));
        bounds.setMaxBoundary(boundary);

        LogSegment segment = new LogSegment(segmentStart, boundary, 0, 231);
        AtomicReference<BoundaryRequest> prompted = new AtomicReference<>();
        strategy.setBoundaryExpansionListener(prompted::set);

        LoadDecision decision = strategy.evaluateLineRequest(231, segment, true);

        assertThat(decision.shouldLoad()).isFalse();
        assertThat(decision.boundaryHit()).isEqualTo(BoundaryType.AFTER);
        assertThat(prompted.get()).isNotNull();
        assertThat(prompted.get().suggestedBoundary()).isAfter(boundary);
    }

    @Test
    void shortBrowsingLoadAutoApprovesEarlierExpansion() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
        SearchBoundaries bounds = new SearchBoundaries(clock);
        Instant boundary = Instant.parse("2026-09-22T00:00:00Z");
        bounds.setMinBoundary(boundary);
        bounds.setMaxBoundary(clock.instant());
        LoadingStrategy strategy = new LoadingStrategy(clock, bounds);
        AtomicReference<BoundaryRequest> prompted = new AtomicReference<>();
        strategy.setBoundaryExpansionListener(prompted::set);
        LogSegment segment = new LogSegment(boundary, boundary.plusSeconds(60), 0, 10);

        strategy.noteUnloadedRowNeeded();
        clock.fastForward(Duration.ofSeconds(9));
        LoadDecision decision = strategy.evaluateLineRequest(-1, segment, true);

        assertThat(prompted.get()).isNull();
        assertThat(bounds.getMinBoundary()).isBefore(boundary);
        assertThat(decision.shouldLoad()).isTrue();
        assertThat(decision.end()).isEqualTo(boundary);
    }

    @Test
    void slowBrowsingLoadStillPromptsAndResetsApprovalWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
        SearchBoundaries bounds = new SearchBoundaries(clock);
        Instant boundary = Instant.parse("2026-09-22T00:00:00Z");
        bounds.setMinBoundary(boundary.minusSeconds(3600));
        bounds.setMaxBoundary(clock.instant());
        LoadingStrategy strategy = new LoadingStrategy(clock, bounds);
        AtomicReference<BoundaryRequest> prompted = new AtomicReference<>();
        strategy.setBoundaryExpansionListener(prompted::set);
        strategy.noteUnloadedRowNeeded();
        clock.fastForward(Duration.ofSeconds(10));
        bounds.setMinBoundary(boundary);

        LoadDecision decision = strategy.evaluateLineRequest(-1,
                new LogSegment(boundary, boundary.plusSeconds(60), 0, 10), true);

        assertThat(decision.shouldLoad()).isFalse();
        assertThat(prompted.get()).isNotNull();
        assertThat(bounds.getMinBoundary()).isEqualTo(boundary);
    }

    @Test
    void prefetchAloneDoesNotAutoApproveLaterExpansion() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
        SearchBoundaries bounds = new SearchBoundaries(clock);
        Instant boundary = Instant.parse("2026-09-22T00:00:00Z");
        bounds.setMinBoundary(boundary.minusSeconds(3600));
        bounds.setMaxBoundary(boundary);
        LoadingStrategy strategy = new LoadingStrategy(clock, bounds);
        AtomicReference<BoundaryRequest> prompted = new AtomicReference<>();
        strategy.setBoundaryExpansionListener(prompted::set);

        LoadDecision decision = strategy.evaluateLineRequest(10,
                new LogSegment(boundary.minusSeconds(60), boundary, 0, 10), false);

        assertThat(decision.shouldLoad()).isFalse();
        assertThat(prompted.get()).isNotNull();
        assertThat(bounds.getMaxBoundary()).isEqualTo(boundary);
    }

    @Test
    @DisplayName("SeekEarlier truncated without known timestamps does not crash the next load")
    void seekEarlierTooBigWithoutTimestampsDoesNotThrow() {
        // SplunkClient.searchResultTooBigException passes null first/last when the
        // events cannot be read; SeekEarlier forwards e.getEnd() as the gap target,
        // so GapVerification must handle a null gap target.
        LogSegment segment = new LogSegment(Instant.parse("2025-05-10T12:00:00Z"),
                Instant.parse("2025-05-10T12:01:00Z"), 0, 10);
        var seek = new DirectionEarlier.SeekEarlier(DAY, 1, 0, DAY);
        LoadDecision decision = seek.evaluateLoad(segment, DAY, -1, "test");

        DirectionEarlier next = seek.recordTooBigResult(new SearchResultToBigException("too big", 3000, null, null),
                decision, DAY);

        assertThatCode(() -> next.evaluateLoad(segment, DAY, -1, "retry")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Truncation where all events share one _time yields a finite velocity")
    void tooBigWithZeroTimeSpanGivesFiniteVelocity() {
        // Second-granular _time makes first == last common. A zero span must not
        // yield an infinite velocity (which would make Normal load 10 ms windows);
        // the queried window is the fallback span.
        Instant second = Instant.parse("2025-05-10T11:55:00Z");
        var seek = new DirectionEarlier.SeekEarlier(DAY, 1, 0, DAY);
        LogSegment segment = new LogSegment(Instant.parse("2025-05-10T12:00:00Z"),
                Instant.parse("2025-05-10T12:01:00Z"), 0, 10);
        LoadDecision decision = seek.evaluateLoad(segment, DAY, -1, "test");

        var next = (DirectionEarlier.GapVerification) seek
                .recordTooBigResult(new SearchResultToBigException("too big", 3000, second, second), decision, DAY);

        assertThat(next.probableVelocity()).isFinite();
    }

    @Test
    @DisplayName("A truncated earlier load is retried with a smaller window, not a larger one")
    void truncatedEarlierLoadShrinksWindow() {
        // With unknown first/last the velocity is estimated from the queried 60
        // second window, so the retry asks for a smaller one instead of 5 minutes.
        MutableClock clock = new MutableClock(Instant.parse("2025-05-10T12:10:00Z"), ZoneOffset.UTC);
        SearchBoundaries boundaries = new SearchBoundaries(clock);
        boundaries.reset(DAY, NEXT_DAY);
        LoadingStrategy strategy = new LoadingStrategy(clock, boundaries);
        Instant segStart = Instant.parse("2025-05-10T12:00:00Z");
        strategy.reset(segStart, 0, DAY, NEXT_DAY);
        LogSegment segment = new LogSegment(segStart, segStart.plusSeconds(60), 0, 10);

        LoadDecision first = strategy.evaluateLineRequest(-1, segment, true);
        strategy.recordTooBigResult(new SearchResultToBigException("too big", 5000, null, null), first, false);
        LoadDecision retry = strategy.evaluateLineRequest(-1, segment, true);

        assertThat(retry.getDuration()).isLessThan(first.getDuration());
    }

    @Test
    @DisplayName("BinarySearchLater halves the range it actually queried after a truncation")
    void binarySearchLaterShrinksClampedProbe() {
        // The probe is clamped to maxBoundary (10 min), so the retry must halve
        // that actual range rather than the unclamped stepSize (24h).
        Instant segEnd = Instant.parse("2025-05-10T12:00:00Z");
        Instant maxBoundary = segEnd.plus(Duration.ofMinutes(10));
        Instant now = Instant.parse("2025-05-10T18:00:00Z");
        LogSegment segment = new LogSegment(segEnd.minusSeconds(60), segEnd, 0, 10);
        DirectionLater state = new DirectionLater.BinarySearchLater(Duration.ofHours(24), 0, maxBoundary);

        LoadDecision first = state.evaluateLoad(segment, maxBoundary, now, 10, "probe 1");
        DirectionLater next = state.recordTooBigResult(
                new SearchResultToBigException("too big", 4000, segEnd, maxBoundary), first, maxBoundary);
        LoadDecision second = next.evaluateLoad(segment, maxBoundary, now, 10, "probe 2");

        assertThat(first.getDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(second.start()).isEqualTo(first.start());
        assertThat(second.getDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("GapVerification probes from the second after the latest truncated log")
    void gapVerificationExcludesTheLatestLogSecond() {
        Instant dense = Instant.parse("2017-12-31T23:52:25Z");
        Instant segStart = Instant.parse("2018-03-21T00:00:00Z");
        Instant min = Instant.parse("1971-01-01T00:00:00Z");
        LogSegment segment = new LogSegment(segStart, segStart.plusSeconds(60), 0, 10);
        var seek = new DirectionEarlier.SeekEarlier(min, 0, 0, min);
        LoadDecision seekDecision = seek.evaluateLoad(segment, min, -1, "seek");

        DirectionEarlier next = seek.recordTooBigResult(new SearchResultToBigException("too big", 3000, dense, dense),
                seekDecision, min);
        LoadDecision probe = next.evaluateLoad(segment, min, -1, "gap");

        assertThat(next).isInstanceOf(DirectionEarlier.GapVerification.class);
        assertThat(probe.shouldLoad()).isTrue();
        assertThat(probe.start()).isEqualTo(dense.plusSeconds(1));
        assertThat(probe.end()).isEqualTo(segStart);
        assertThat(probe.maxEvents()).isEmpty();
        assertThat(probe.reason()).contains("gap verification");
    }

    @Test
    @DisplayName("GapVerification truncates a sub-second latest log to its whole second")
    void gapVerificationTruncatesSubSecondLatestLog() {
        Instant latest = Instant.parse("2025-05-10T11:55:00.750Z");
        Instant segStart = Instant.parse("2025-05-10T12:00:00Z");
        LogSegment segment = new LogSegment(segStart, segStart.plusSeconds(60), 0, 10);
        var gap = new DirectionEarlier.GapVerification(latest, 100, 5000, 1, DAY);

        LoadDecision probe = gap.evaluateLoad(segment, DAY, -1, "gap");

        assertThat(probe.start()).isEqualTo(Instant.parse("2025-05-10T11:55:01Z"));
    }

    @Test
    @DisplayName("GapVerification falls back to the velocity window when the latest second reaches the segment start")
    void gapVerificationWithLatestSecondAtSegmentStartIsVelocityBased() {
        Instant latest = Instant.parse("2025-05-10T11:59:59Z");
        Instant segStart = Instant.parse("2025-05-10T11:59:59.500Z");
        LogSegment segment = new LogSegment(segStart, segStart.plusSeconds(60), 0, 10);
        var gap = new DirectionEarlier.GapVerification(latest, 100, 5000, 2, DAY);

        LoadDecision probe = gap.evaluateLoad(segment, DAY, -1, "gap");

        assertThat(probe.shouldLoad()).isTrue();
        assertThat(probe.reason()).contains("velocity-based");
        assertThat(probe.end()).isEqualTo(segStart);
        assertThat(probe.start()).isBefore(latest);
    }

    @Test
    @DisplayName("BinarySearchLater probes the whole second instead of halving below one second")
    void binarySearchLaterTooBigBelowOneSecondProbesWholeSecond() {
        Instant segEnd = Instant.parse("2017-12-31T23:52:25Z");
        Instant maxBoundary = segEnd.plus(Duration.ofHours(1));
        Instant now = Instant.parse("2025-05-10T18:00:00Z");
        LogSegment segment = new LogSegment(segEnd.minusSeconds(60), segEnd, 0, 10);
        DirectionLater state = new DirectionLater.BinarySearchLater(Duration.ofMillis(1500), 0, maxBoundary);
        LoadDecision probe = state.evaluateLoad(segment, maxBoundary, now, 10, "probe");

        DirectionLater next = state.recordTooBigResult(
                new SearchResultToBigException("too big", 3000, segEnd, maxBoundary), probe, maxBoundary);
        LoadDecision after = next.evaluateLoad(segment, maxBoundary, now, 10, "after");

        assertThat(next).isInstanceOf(DirectionLater.BinarySearchLater.class);
        assertThat(after.start()).isEqualTo(segEnd);
        assertThat(after.end()).isEqualTo(segEnd.plusSeconds(1));
        assertThat(after.withinOneSecond()).isTrue();
    }

    @Test
    @DisplayName("BinarySearchLater cuts a sub-two-second TooBig probe at the next whole second")
    void binarySearchLaterCutsAtWholeSecond() {
        Instant segEnd = Instant.parse("2017-12-31T23:52:24.900Z");
        Instant maxBoundary = segEnd.plus(Duration.ofHours(1));
        Instant now = Instant.parse("2025-05-10T18:00:00Z");
        LogSegment segment = new LogSegment(segEnd.minusSeconds(60), segEnd, 0, 10);
        DirectionLater state = new DirectionLater.BinarySearchLater(Duration.ofMillis(1500), 0, maxBoundary);
        LoadDecision probe = state.evaluateLoad(segment, maxBoundary, now, 10, "probe");

        DirectionLater next = state.recordTooBigResult(
                new SearchResultToBigException("too big", 3000, segEnd, maxBoundary), probe, maxBoundary);
        LoadDecision cut = next.evaluateLoad(segment, maxBoundary, now, 10, "cut");

        assertThat(next).isInstanceOf(DirectionLater.BinarySearchLater.class);
        assertThat(cut.start()).isEqualTo(segEnd);
        assertThat(cut.end()).isEqualTo(Instant.parse("2017-12-31T23:52:25Z"));
        assertThat(cut.withinOneSecond()).isTrue();
    }

    @Test
    @DisplayName("Normal UP loads the whole second before the segment start when the window would be shorter")
    void normalEarlierBelowOneSecondLoadsWholeSecond() {
        Instant segStart = Instant.parse("2017-12-31T23:52:26Z");
        Instant min = Instant.parse("1971-01-01T00:00:00Z");
        LogSegment segment = new LogSegment(segStart, segStart.plusSeconds(60), 0, 10);
        var normal = new DirectionEarlier.Normal(1_000_000, 2500, min);

        LoadDecision decision = normal.evaluateLoad(segment, min, -1, "dense");

        assertThat(decision.start()).isEqualTo(Instant.parse("2017-12-31T23:52:25Z"));
        assertThat(decision.end()).isEqualTo(segStart);
        assertThat(decision.withinOneSecond()).isTrue();
    }

    @Test
    @DisplayName("Normal UP from a sub-second segment start loads the rest of that second")
    void normalEarlierBelowOneSecondFromUnalignedStart() {
        Instant segStart = Instant.parse("2017-12-31T23:52:26.300Z");
        Instant min = Instant.parse("1971-01-01T00:00:00Z");
        LogSegment segment = new LogSegment(segStart, segStart.plusSeconds(60), 0, 10);
        var normal = new DirectionEarlier.Normal(1_000_000, 2500, min);

        LoadDecision decision = normal.evaluateLoad(segment, min, -1, "dense");

        assertThat(decision.start()).isEqualTo(Instant.parse("2017-12-31T23:52:26Z"));
        assertThat(decision.withinOneSecond()).isTrue();
    }

    @Test
    @DisplayName("BinarySearchLater keeps halving while the halved probe is at least one second")
    void binarySearchLaterKeepsHalvingAtOneSecond() {
        Instant segEnd = Instant.parse("2017-12-31T23:52:25Z");
        Instant maxBoundary = segEnd.plus(Duration.ofHours(1));
        Instant now = Instant.parse("2025-05-10T18:00:00Z");
        LogSegment segment = new LogSegment(segEnd.minusSeconds(60), segEnd, 0, 10);
        DirectionLater state = new DirectionLater.BinarySearchLater(Duration.ofSeconds(2), 0, maxBoundary);
        LoadDecision probe = state.evaluateLoad(segment, maxBoundary, now, 10, "probe");

        DirectionLater next = state.recordTooBigResult(
                new SearchResultToBigException("too big", 3000, segEnd, maxBoundary), probe, maxBoundary);

        assertThat(next).isInstanceOf(DirectionLater.BinarySearchLater.class);
        assertThat(next.evaluateLoad(segment, maxBoundary, now, 10, "next").getDuration())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("A sparse load reaching the absolute min boundary never moves the boundary before it")
    void sparseLoadAtAbsoluteMinDoesNotExpandBelowIt() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T09:40:00Z"), ZoneOffset.UTC);
        SearchBoundaries bounds = new SearchBoundaries(clock);
        LoadingStrategy strategy = new LoadingStrategy(clock, bounds);
        Instant min = SearchBoundaries.MIN_MINIMUM_BOUNDARY;
        Instant segStart = Instant.parse("2017-03-01T00:00:00Z");
        bounds.setMinBoundary(min);
        bounds.setMaxBoundary(Instant.parse("2026-10-29T00:00:00Z"));

        // The forever seek [MIN, segStart) finds a handful of hits: tiny velocity.
        var loaded = new LoadedLogLines(min, segStart, fakeLoadedLines(3, segStart.minusSeconds(3), segStart));
        strategy.recordLoadResult(-1, loaded, LoadDecision.load(null, -1, min, segStart, "seek"));

        assertThat(bounds.getMinBoundary()).isEqualTo(min);

        // The segment reaches back to MIN: scrolling above it must not query before MIN.
        LogSegment segment = new LogSegment(min, segStart, -3, 0);
        LoadDecision decision = strategy.evaluateLineRequest(-4, segment, true);
        assertThat(decision.shouldLoad()).isFalse();
    }

    @Test
    void searchBoundariesClampMinBoundaryToAbsoluteMin() {
        SearchBoundaries bounds = new SearchBoundaries(Clock.systemUTC());
        bounds.setMinBoundary(Instant.parse("1970-12-31T00:00:00Z"));
        assertThat(bounds.getMinBoundary()).isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY);
    }

    private List<LoadedLogLine> fakeLoadedLines(int count, Instant start, Instant end) {

        int lineNo = 0; // tests always start at 0
        List<LoadedLogLine> result = new ArrayList<>(Math.max(count, 0));

        if (count <= 0) {
            return result;
        }

        long deltaMillis = Duration.between(start, end).toMillis() / count;

        for (int n = 0; n < count; n++) {
            Instant t = start.plusMillis(deltaMillis * n);
            result.add(new LoadedLogLine(JSON, "s1", lineNo + n, "cb" + (lineNo + n), "bkt" + (lineNo + n), t,
                    "msg" + (lineNo + n), t, t, "Log line " + (lineNo + n), false, Level.INFO));
        }

        return result;
    }

    /* Test
        21:29:34.692 [AWT-EventQueue-0] AWT-EventQueue-0 DEBUG handleCancel: Completed se.grynna.pager.ui.login.IndexPanel
    21:29:39.938 [AWT-EventQueue-0] AWT-EventQueue-0 DEBUG Boundaries: BEFORE 2026-01-25T00:00:00Z(-597), AFTER 2026-01-27T00:00:00Z(2), NOW 2026-01-26T20:24:00Z(-) s.g.f.splunk.SplunkLogDataProvider
    21:29:39.938 [AWT-EventQueue-0] AWT-EventQueue-0 DEBUG ELR: Evaluating line=2 offset=1 bufferSize=3000 s.g.pager.splunk.LoadingStrategy
    21:29:39.938 [AWT-EventQueue-0] AWT-EventQueue-0 DEBUG DOWN AtNowBoundary: NOW advanced 2026-01-26T20:20:00Z -> 2026-01-26T20:24:00Z, resuming s.grynna.pager.splunk.DirectionLater
    21:29:39.938 [AWT-EventQueue-0] AWT-EventQueue-0 INFO DOWN now advanced: AtNowBoundary(lastNow=2026-01-26T20:20:00Z) -> BinarySearch(step=PT0S) s.g.pager.splunk.LoadingStrategy
    21:29:39.939 [AWT-EventQueue-0] AWT-EventQueue-0 DEBUG DOWN BinarySearch: probe from 2026-01-26T20:20:00Z step PT0S s.grynna.pager.splunk.DirectionLater
    21:29:39.940 [pool-4-thread-1] pool-4-thread-1 DEBUG load: LoadDecision{ dir=1,shouldLoad=true, 2026-01-26T20:20:00Z - 2026-01-26T20:20:00Z, duration=PT0S, reason='getLine 2 after segment BinarySearch(step=PT0S)', boundaryHit=null, maxEvents=3000, state=BinarySearch(step=PT0S)} s.g.f.splunk.SplunkLogDataProvider
    21:29:39.940 [pool-4-thread-1] pool-4-thread-1 ERROR Error scrolling s.g.f.splunk.SplunkLogDataProvider
    java.lang.IllegalArgumentException: Start must be before end: LoadDecision{ dir=1,shouldLoad=true, 2026-01-26T20:20:00Z - 2026-01-26T20:20:00Z, duration=PT0S, reason='getLine 2 after segment BinarySearch(step=PT0S)', boundaryHit=null, maxEvents=3000, state=BinarySearch(step=PT0S)} s.g.f.splunk.SplunkLogDataProvider
         */
}
