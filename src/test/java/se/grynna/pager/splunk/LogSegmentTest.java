package se.grynna.pager.splunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static se.grynna.pager.testutils.SegmentTestUtil.*;

class LogSegmentTest {

    private static final Instant BASE_TIME = Instant.parse("2023-05-13T14:00:00Z");
    private static final Instant END_TIME = Instant.parse("2023-05-13T14:10:00Z");

    // ==================== Constructor Tests ====================

    @Test
    @DisplayName("Constructor initializes all fields correctly")
    void constructorInitializesCorrectly() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.getStartTime()).isEqualTo(BASE_TIME);
        assertThat(segment.getEndTime()).isEqualTo(END_TIME);
        assertThat(segment.getStartLine()).isEqualTo(100);
        assertThat(segment.size()).isEqualTo(50);
        assertThat(segment.getEndLineExl()).isEqualTo(150);
        assertThat(segment.getId()).isNotNull();
    }

    @Test
    @DisplayName("Constructor with zero size")
    void constructorWithZeroSize() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 0);
        assertThat(segment.size()).isEqualTo(0);
        assertThat(segment.getEndLineExl()).isEqualTo(100);
    }

    @Test
    void offsetEmptyZero() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 0, 0);
        assertThat(segment.size()).isEqualTo(0);
        assertThat(segment.getEndLineExl()).isEqualTo(0);
        assertThat(segment.getStartLine()).isEqualTo(0);
        assertThat(segment.offset(0)).as("Special case: offset(0) for empty segment return -1").isEqualTo(-1);
    }

    @Test
    void offsetEmptyMinus1() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 0, 0);
        assertThat(segment.size()).isEqualTo(0);
        assertThat(segment.getEndLineExl()).isEqualTo(0);
        assertThat(segment.getStartLine()).isEqualTo(0);
        assertThat(segment.offset(-1)).isEqualTo(-1);
    }

    @Test
    void offsetEmptyPlus1() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 0, 0);
        assertThat(segment.size()).isEqualTo(0);
        assertThat(segment.getEndLineExl()).isEqualTo(0);
        assertThat(segment.getStartLine()).isEqualTo(0);
        assertThat(segment.offset(1)).isEqualTo(2);
    }

    @Test
    void offsetOnePlus1() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 0, 1);
        assertThat(segment.size()).isEqualTo(1);
        assertThat(segment.getEndLineExl()).isEqualTo(1);
        assertThat(segment.getStartLine()).isEqualTo(0);
        assertThat(segment.offset(1)).isEqualTo(1);
    }

    @Test
    void offsetEmpty100() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 0);
        assertThat(segment.size()).isEqualTo(0);
        assertThat(segment.getEndLineExl()).isEqualTo(100);
        assertThat(segment.getStartLine()).isEqualTo(100);
        assertThat(segment.offset(100)).as("Special case: offset(startLine) for empty segment return -1").isEqualTo(-1);
    }

    // ==================== contains(int line) Tests ====================

    @Test
    @DisplayName("contains(line) - line at startLine is included")
    void containsLineAtStart() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.contains(100)).isTrue();
    }

    @Test
    @DisplayName("contains(line) - line at last position is included")
    void containsLineAtLastPosition() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.contains(149)).isTrue();
    }

    @Test
    @DisplayName("contains(line) - line at endLineExl is excluded")
    void containsLineAtEndExclusive() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.contains(150)).isFalse();
    }

    @Test
    @DisplayName("contains(line) - line before startLine is excluded")
    void containsLineBeforeStart() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.contains(99)).isFalse();
    }

    @Test
    @DisplayName("contains(line) - line in middle is included")
    void containsLineInMiddle() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.contains(125)).isTrue();
    }

    @Test
    @DisplayName("contains(line) - empty segment contains nothing")
    void containsLineEmptySegment() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 0);

        assertThat(segment.contains(99)).isFalse();
        assertThat(segment.contains(101)).isFalse();
    }

    @Test
    @DisplayName("contains(line) - negative line numbers")
    void containsNegativeLineNumbers() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 0, 50);

        assertThat(segment.contains(0)).isTrue();
    }

    @Test
    @DisplayName("contains(line) - single line segment")
    void containsSingleLineSegment() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 1);

        assertThat(segment.contains(101)).isFalse();
        assertThat(segment.contains(99)).isFalse();
    }

    // ==================== contains(Instant) Tests ====================

    @Test
    @DisplayName("contains(timestamp) - timestamp at startTime is included")
    void containsTimestampAtStart() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.contains(BASE_TIME)).isTrue();
    }

    @Test
    @DisplayName("contains(timestamp) - timestamp at endTime is excluded")
    void containsTimestampAtEnd() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.contains(END_TIME)).isFalse();
    }

    @Test
    @DisplayName("contains(timestamp) - timestamp just after startTime is included")
    void containsTimestampJustAfterStart() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant justAfter = BASE_TIME.plusNanos(1);
        assertThat(segment.contains(justAfter)).isTrue();
    }

    @Test
    @DisplayName("contains(timestamp) - timestamp just before endTime is included")
    void containsTimestampJustBeforeEnd() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant justBefore = END_TIME.minusNanos(1);
        assertThat(segment.contains(justBefore)).isTrue();
    }

    @Test
    @DisplayName("contains(timestamp) - timestamp in middle is included")
    void containsTimestampInMiddle() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant middle = BASE_TIME.plusSeconds(300); // 5 minutes after start
        assertThat(segment.contains(middle)).isTrue();
    }

    @Test
    @DisplayName("contains(timestamp) - timestamp before range")
    void containsTimestampBeforeRange() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant before = BASE_TIME.minusSeconds(60);
        assertThat(segment.contains(before)).isFalse();
    }

    @Test
    @DisplayName("contains(timestamp) - timestamp after range")
    void containsTimestampAfterRange() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant after = END_TIME.plusSeconds(60);
        assertThat(segment.contains(after)).isFalse();
    }

    // ==================== Consistency Tests ====================

    @Test
    @DisplayName("Consistency - startTime inclusive matches startLine inclusive")
    void consistencyStartTimeInclusive() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);

        assertThat(segment.contains(100)).isTrue();
    }

    @Test
    @DisplayName("Consistency - endTime exclusive matches endLine exclusive")
    void consistencyEndTimeExclusive() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);

        assertThat(segment.contains(150)).isFalse();
    }

    @Test
    @DisplayName("Edge case - zero-duration segment")
    void edgeCaseZeroDurationSegment() {
        LogSegment segment = createTestSegment(BASE_TIME, BASE_TIME, 100, 0);
        assertThat(segment.getStartTime()).isEqualTo(BASE_TIME);
        assertThat(segment.getEndTime()).isEqualTo(BASE_TIME);
        assertThat(segment.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Edge case - single nanosecond segment")
    void edgeCaseSingleNanosecondSegment() {
        Instant end = BASE_TIME.plusNanos(1);
        LogSegment segment = createTestSegment(BASE_TIME, end, 100, 1);
        assertThat(segment.contains(BASE_TIME)).isTrue();
        assertThat(segment.contains(end)).isFalse();
    }

    // ==================== offset Tests ====================

    @Test
    @DisplayName("offset - index before startLine returns negative")
    void offsetBeforeStart() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.offset(99)).isNegative();
    }

    @Test
    @DisplayName("offset - index at startLine returns 0")
    void offsetAtStart() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.offset(100)).isEqualTo(0);
    }

    @Test
    @DisplayName("offset - index inside segment returns 0")
    void offsetInsideSegment() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.offset(125)).isEqualTo(0);
    }

    @Test
    @DisplayName("offset - index at endLineExl returns positive")
    void offsetAtEndExclusive() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.offset(150)).isPositive();
    }

    @Test
    @DisplayName("offset - empty segment at startLine returns 1")
    void offsetEmptySegmentAtStart() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 0);
        assertThat(segment.offset(100)).isEqualTo(-1);
    }

    // ==================== add Tests (replaces append/prepend) ====================

    @Test
    @DisplayName("add - adds size and updates endTime")
    void appendAddsSize() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant newEndTime = END_TIME.plusSeconds(60);
        segment.add(makeLoadDecision(1, segment.getEndTime(), newEndTime),
                makeLoadedLines(10, segment.getEndTime(), newEndTime));
        assertThat(segment.getEndTime()).isEqualTo(newEndTime);
        assertThat(segment.size()).isEqualTo(60);
    }

    @Test
    @DisplayName("add - throws on null endTime")
    void appendThrowsOnNullEndTime() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThatThrownBy(() -> segment.add(LoadDecision.load(null, 1, segment.getEndTime(), null, "bad"),
                makeLoadedLines(10, segment.getEndTime(), END_TIME))).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("add - endTime before, does not affect current endTime")
    void appendEndTimeUnaffectedIfBefore() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant earlier = BASE_TIME.minusSeconds(60);
        segment.add(LoadDecision.load(null, -1, earlier, BASE_TIME, "bad"),
                makeLoadedLines(10, segment.getEndTime(), earlier));
        assertThat(segment.getEndTime()).isEqualTo(END_TIME);
    }

    // ==================== prepend Tests ====================

    @Test
    @DisplayName("prepend - adds size and updates startTime and startLine")
    void prependAddsSize() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant newStartTime = BASE_TIME.minusSeconds(60);
        segment.add(makeLoadDecision(-1, newStartTime, segment.getStartTime()),
                makeLoadedLines(10, newStartTime, segment.getStartTime()));
        assertThat(segment.getStartLine()).isEqualTo(90);
        assertThat(segment.size()).isEqualTo(60);
    }

    @Test
    @DisplayName("prepend - can result in negative startLine")
    void prependCanMakeNegativeStartLine() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 10, 50);
        Instant newStartTime = BASE_TIME.minusSeconds(60);
        segment.add(makeLoadDecision(-1, newStartTime, segment.getStartTime()),
                makeLoadedLines(20, newStartTime, segment.getStartTime()));
        assertThat(segment.getStartLine()).isEqualTo(-10);
    }

    @Test
    @DisplayName("prepend - throws on null startTime")
    void prependThrowsOnNullStartTime() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThatThrownBy(() -> segment.add(LoadDecision.load(null, 0, null, segment.getStartTime(), "bad"),
                makeLoadedLines(10, BASE_TIME, segment.getStartTime()))).isInstanceOf(NullPointerException.class);
    }

    // ==================== init Tests ====================

    @Test
    @DisplayName("init - initializes empty segment")
    void initInitializesEmptySegment() {
        LogSegment segment = new LogSegment(BASE_TIME);
        LoadedLogLines loaded = makeLoadedLines(50, BASE_TIME, END_TIME);
        segment.init(BASE_TIME, LoadDecision.load(null, 0, BASE_TIME, END_TIME, "init"), loaded);
        assertThat(segment.getStartTime()).isEqualTo(BASE_TIME);
        assertThat(segment.getEndTime()).isEqualTo(END_TIME);
        assertThat(segment.getStartLine()).isEqualTo(0);
        assertThat(segment.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("init - throws when segment already initialized")
    void initThrowsWhenAlreadyInitialized() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        LoadedLogLines loaded = makeLoadedLines(50, BASE_TIME, END_TIME);
        assertThatThrownBy(
                () -> segment.init(BASE_TIME, LoadDecision.load(null, 0, BASE_TIME, END_TIME, "init"), loaded))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Segment already initialized");
    }

    // ==================== compareTo Tests ====================

    @Test
    @DisplayName("compareTo - segments ordered by startTime")
    void compareToOrdersByStartTime() {
        Instant earlier = BASE_TIME.minusSeconds(60);
        Instant later = BASE_TIME.plusSeconds(60);
        LogSegment segment1 = createTestSegment(earlier, END_TIME, 100, 50);
        LogSegment segment2 = createTestSegment(later, END_TIME, 100, 50);
        assertThat(segment1.compareTo(segment2)).isNegative();
        assertThat(segment2.compareTo(segment1)).isPositive();
    }

    @Test
    @DisplayName("compareTo - identical segments return 0")
    void compareToIdenticalSegments() {
        LogSegment segment1 = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        LogSegment segment2 = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment1.compareTo(segment2)).isEqualTo(0);
    }

    // ==================== Complex Scenarios ====================

    @Test
    @DisplayName("Complex - multiple operations")
    void complexMultipleOperations() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        segment.add(makeLoadDecision(1, segment.getEndTime(), END_TIME.plusSeconds(60)),
                makeLoadedLines(20, segment.getEndTime(), END_TIME.plusSeconds(60)));
        assertThat(segment.size()).isEqualTo(70);
        segment.add(makeLoadDecision(-1, BASE_TIME.minusSeconds(60), segment.getStartTime()),
                makeLoadedLines(10, BASE_TIME.minusSeconds(60), segment.getStartTime()));
        assertThat(segment.getStartLine()).isEqualTo(90);
    }

    @Test
    @DisplayName("Complex - boundaries after operations")
    void complexSegmentBoundariesAfterOperations() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        segment.add(makeLoadDecision(-1, BASE_TIME.minusSeconds(60), segment.getStartTime()),
                makeLoadedLines(25, BASE_TIME.minusSeconds(60), segment.getStartTime()));
        assertThat(segment.contains(75)).isTrue();
        assertThat(segment.contains(149)).isTrue();
        assertThat(segment.contains(150)).isFalse();
    }

    @Test
    @DisplayName("offset - index at last line returns 0")
    void offsetAtLastLine() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.offset(149)).isEqualTo(0);
    }

    @Test
    @DisplayName("offset - index after endLineExl returns positive")
    void offsetAfterEnd() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.offset(151)).isPositive();
    }

    @Test
    @DisplayName("offset - empty segment before startLine returns negative")
    void offsetEmptySegmentBefore() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 0);
        assertThat(segment.offset(99)).isNegative();
    }

    @Test
    @DisplayName("offset - empty segment at/after startLine returns positive")
    void offsetEmptySegmentAfter() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 0);
        assertThat(segment.offset(101)).isPositive();
    }

    @Test
    @DisplayName("add - with zero size works")
    void appendWithZeroSize() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        segment.add(makeLoadDecision(1, segment.getEndTime(), END_TIME.plusSeconds(10)),
                makeLoadedLines(0, segment.getEndTime(), END_TIME.plusSeconds(10)));
        assertThat(segment.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("prepend - with zero size works")
    void prependWithZeroSize() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        segment.add(makeLoadDecision(-1, BASE_TIME.minusSeconds(10), segment.getStartTime()),
                makeLoadedLines(0, BASE_TIME.minusSeconds(10), segment.getStartTime()));
        assertThat(segment.getStartLine()).isEqualTo(100);
        assertThat(segment.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("prepend - throws when startTime is after current startTime")
    void prependThrowsWhenStartTimeIsAfter() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        Instant later = BASE_TIME.plusSeconds(60);
        assertThatThrownBy(() -> segment.add(makeLoadDecision(-1, later, segment.getStartTime()),
                makeLoadedLines(10, later, segment.getStartTime()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("init - can initialize with zero size")
    void initWithZeroSize() {
        LogSegment segment = new LogSegment(BASE_TIME);
        LoadedLogLines loaded = makeLoadedLines(0, BASE_TIME, END_TIME);
        segment.init(BASE_TIME, LoadDecision.load(null, 0, BASE_TIME, END_TIME, "init"), loaded);
        assertThat(segment.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("init - cannot be called twice on empty segment")
    void initCannotBeCalledTwice() {
        LogSegment segment = new LogSegment(BASE_TIME);
        LoadedLogLines loaded = makeLoadedLines(50, BASE_TIME, END_TIME);
        segment.init(BASE_TIME, LoadDecision.load(null, 0, BASE_TIME, END_TIME, "init"), loaded);
        assertThatThrownBy(
                () -> segment.init(BASE_TIME, LoadDecision.load(null, 0, BASE_TIME, END_TIME, "init2"), loaded))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("add - retry on empty segment uses preserved target and assigns closest event to row zero")
    void addOnEmptySegmentUsesPreservedTarget() {
        Instant target = BASE_TIME.plusSeconds(30);
        LogSegment segment = new LogSegment(BASE_TIME);
        segment.preserveInitialTarget(target);

        Instant start = target.minusSeconds(20);
        Instant end = target.plusSeconds(10);
        // Evenly spread timestamps: target-20s, target-5s, target+10s.
        LoadedLogLines loaded = makeLoadedLines(3, start, end);

        int startLine = segment.add(makeLoadDecision(-1, start, target), loaded);

        // The event at target-5s is closest to the target and must be row zero.
        assertThat(startLine).isEqualTo(-1);
        assertThat(segment.getStartLine()).isEqualTo(-1);
        assertThat(segment.size()).isEqualTo(3);
        assertThat(segment.getInitialTargetTimestamp()).isEqualTo(target);
    }

    @Test
    @DisplayName("init - exact and nearest targets preserve nanosecond precision")
    void initPreservesNanosecondPrecisionWhenChoosingRowZero() {
        Instant base = BASE_TIME;
        List<LoadedLogLine> lines = List.of(logLineAt(base.plusNanos(100)), logLineAt(base.plusNanos(200)),
                logLineAt(base.plusNanos(300)));
        LoadedLogLines loaded = new LoadedLogLines(base, base.plusSeconds(1), lines);
        LoadDecision decision = makeLoadDecision(0, base, base.plusSeconds(1));

        LogSegment exact = new LogSegment(base.plusNanos(300));
        assertThat(exact.init(base.plusNanos(300), decision, loaded)).isEqualTo(-2);

        LogSegment nearest = new LogSegment(base.plusNanos(260));
        assertThat(nearest.init(base.plusNanos(260), decision, loaded)).isEqualTo(-2);
    }

    @Test
    @DisplayName("init - preserved search hit chooses exact event when Splunk times are equal")
    void initUsesPreservedSearchHitForEqualSplunkTimeEvents() {
        Instant base = BASE_TIME;
        Instant splunkTime = base.plusSeconds(5);

        LoadedLogLine first = new LoadedLogLine(LoadedLogLine.MsgType.RAW, "segment", 0, "cdA", "bktA", splunkTime,
                "rawA", splunkTime, splunkTime.plusNanos(100), "A", false, Level.INFO);
        LoadedLogLine second = new LoadedLogLine(LoadedLogLine.MsgType.RAW, "segment", 0, "cdB", "bktB", splunkTime,
                "rawB", splunkTime, splunkTime.plusNanos(200), "B", false, Level.INFO);

        LoadedLogLines loaded = new LoadedLogLines(base, base.plusSeconds(10), List.of(first, second));
        LoadDecision decision = makeLoadDecision(0, base, base.plusSeconds(10));

        LogSegment segment = new LogSegment(splunkTime);
        segment.preserveInitialTarget(splunkTime);
        segment.preserveInitialTarget(new SearchHit(splunkTime, splunkTime.plusNanos(200), "bktB", "cdB"));

        int startLine = segment.add(decision, loaded);

        assertThat(startLine).isEqualTo(-1);
        assertThat(segment.getStartLine()).isEqualTo(-1);
        assertThat(segment.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("init - exact timestamp prefers event start over continuation")
    void initExactTimestampPrefersEventStartOverContinuation() {
        Instant base = BASE_TIME;
        Instant t = base.plusSeconds(5);

        LoadedLogLine first = new LoadedLogLine(LoadedLogLine.MsgType.RAW, "segment", 0, "cdMulti", "bktMulti", t,
                "raw", t, t, "firstLine", false, Level.INFO);
        LoadedLogLine continuation = new LoadedLogLine(LoadedLogLine.MsgType.RAW, "segment", 0, "cdMulti", "bktMulti",
                t, "raw", t, t, "traceLine", true, Level.INFO);
        LoadedLogLine later = new LoadedLogLine(LoadedLogLine.MsgType.RAW, "segment", 0, "cdLater", "bktLater",
                t.plusSeconds(1), "rawLater", t.plusSeconds(1), t.plusSeconds(1), "later", false, Level.INFO);

        LoadedLogLines loaded = new LoadedLogLines(base, base.plusSeconds(10), List.of(first, continuation, later));
        LoadDecision decision = makeLoadDecision(0, base, base.plusSeconds(10));

        LogSegment segment = new LogSegment(t);
        int startLine = segment.init(t, decision, loaded);

        assertThat(startLine).isZero();
        assertThat(segment.getStartLine()).isZero();
        assertThat(segment.size()).isEqualTo(3);
    }

    private static LoadedLogLine logLineAt(Instant time) {
        return new LoadedLogLine(LoadedLogLine.MsgType.RAW, "segment", 0, "cd", "bkt", time, "raw", time, time,
                "message", false, Level.INFO);
    }

    @Test
    @DisplayName("add - empty segment without preserved target fails explicitly")
    void addOnEmptySegmentWithoutPreservedTargetFails() {
        LogSegment segment = new LogSegment(BASE_TIME);
        LoadDecision decision = makeLoadDecision(-1, BASE_TIME.minusSeconds(10), BASE_TIME);
        LoadedLogLines loaded = makeLoadedLines(1, BASE_TIME.minusSeconds(10), BASE_TIME);

        assertThatThrownBy(() -> segment.add(decision, loaded)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initial target timestamp");
    }

    @Test
    @DisplayName("preserveInitialTarget - rejects null target")
    void preserveInitialTargetRejectsNull() {
        LogSegment segment = new LogSegment(BASE_TIME);

        assertThatThrownBy(() -> segment.preserveInitialTarget((Instant) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("target cannot be null");
    }

    @Test
    @DisplayName("preserveInitialTarget(SearchHit) - rejects null target hit")
    void preserveInitialTargetHitRejectsNull() {
        LogSegment segment = new LogSegment(BASE_TIME);

        assertThatThrownBy(() -> segment.preserveInitialTarget((SearchHit) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("targetHit cannot be null");
    }

    @Test
    @DisplayName("compareTo - same startTime ordered by endTime")
    void compareToOrdersByEndTimeWhenStartTimeEqual() {
        Instant endEarlier = END_TIME.minusSeconds(60);
        Instant endLater = END_TIME.plusSeconds(60);
        LogSegment segment1 = createTestSegment(BASE_TIME, endEarlier, 100, 50);
        LogSegment segment2 = createTestSegment(BASE_TIME, endLater, 100, 50);
        assertThat(segment1.compareTo(segment2)).isNegative();
        assertThat(segment2.compareTo(segment1)).isPositive();
    }

    @Test
    @DisplayName("compareTo - line numbers don't affect ordering")
    void compareToIgnoresLineNumbers() {
        LogSegment segment1 = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        LogSegment segment2 = createTestSegment(BASE_TIME, END_TIME, 200, 30);
        assertThat(segment1.compareTo(segment2)).isEqualTo(0);
    }

    @Test
    @DisplayName("getEndLineExl - returns startLine + size")
    void getEndLineExlCalculation() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.getEndLineExl()).isEqualTo(150);
    }

    @Test
    @DisplayName("getEndLineExl - with zero size returns startLine")
    void getEndLineExlWithZeroSize() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 0);
        assertThat(segment.getEndLineExl()).isEqualTo(100);
    }

    @Test
    @DisplayName("Complex - offset calculation after prepend")
    void complexOffsetAfterPrepend() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        segment.add(makeLoadDecision(-1, BASE_TIME.minusSeconds(60), segment.getStartTime()),
                makeLoadedLines(25, BASE_TIME.minusSeconds(60), segment.getStartTime()));
        assertThat(segment.offset(75)).isEqualTo(0);
        assertThat(segment.offset(100)).isEqualTo(0);
        assertThat(segment.offset(149)).isEqualTo(0);
        assertThat(segment.offset(150)).isEqualTo(1);
        assertThat(segment.offset(155)).isEqualTo(6);
    }

    // xxxxx.....
    // 7         0
    // 01234567890
    //

    @Test
    @DisplayName("Edge case - segment with negative startLine")
    void edgeCaseNegativeStartLine() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 10, 20);
        segment.add(makeLoadDecision(-1, BASE_TIME.minusSeconds(60), segment.getStartTime()),
                makeLoadedLines(50, BASE_TIME.minusSeconds(60), segment.getStartTime()));
        assertThat(segment.size()).isEqualTo(70);
        assertThat(segment.getEndLineExl()).isEqualTo(30);
        assertThat(segment.contains(-40)).isTrue();
        assertThat(segment.contains(-41)).isFalse();
        assertThat(segment.contains(0)).isTrue();
        assertThat(segment.contains(29)).isTrue();
        assertThat(segment.contains(30)).isFalse();
    }

    @Test
    @DisplayName("Complex - multiple appends and prepends")
    void complexMultipleAppendsAndPrepends() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        segment.add(makeLoadDecision(1, segment.getEndTime(), END_TIME.plusSeconds(60)),
                makeLoadedLines(20, segment.getEndTime(), END_TIME.plusSeconds(60)));
        assertThat(segment.size()).isEqualTo(70);
        assertThat(segment.getEndLineExl()).isEqualTo(170);
        segment.add(makeLoadDecision(-1, BASE_TIME.minusSeconds(60), segment.getStartTime()),
                makeLoadedLines(10, BASE_TIME.minusSeconds(60), segment.getStartTime()));
        assertThat(segment.getStartLine()).isEqualTo(90);
        assertThat(segment.getEndLineExl()).isEqualTo(170);
        segment.add(makeLoadDecision(1, segment.getEndTime(), END_TIME.plusSeconds(120)),
                makeLoadedLines(5, segment.getEndTime(), END_TIME.plusSeconds(120)));
        assertThat(segment.size()).isEqualTo(85);
        assertThat(segment.getEndLineExl()).isEqualTo(175);
    }

    // test distance to end / start
    @Test
    @DisplayName("distanceToEnd and distanceToStart calculations")
    void distanceToEndAndStartCalculations() {
        LogSegment segment = createTestSegment(BASE_TIME, END_TIME, 100, 50);
        assertThat(segment.distanceToStart(90)).isEqualTo(-10);
        assertThat(segment.distanceToStart(100)).isEqualTo(0);
        assertThat(segment.distanceToStart(125)).isEqualTo(25);
        assertThat(segment.distanceToStart(150)).isEqualTo(50);
        assertThat(segment.distanceToEnd(90)).isEqualTo(60);
        assertThat(segment.distanceToEnd(100)).isEqualTo(50);
        assertThat(segment.distanceToEnd(125)).isEqualTo(25);
        assertThat(segment.distanceToEnd(150)).isEqualTo(0);
        assertThat(segment.distanceToEnd(160)).isEqualTo(-10);
    }
}
