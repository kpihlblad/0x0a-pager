package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.AfterBoundaryLine;
import se.grynna.pager.splunk.BeforeBoundaryLine;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Placeholder;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class MatchesDataProviderTest {

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    private static final Instant ORIGIN = Instant.parse("2023-01-01T00:00:00Z");
    private static final Instant T0 = Instant.parse("2023-01-01T12:00:00Z");

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    private static LoadedLogLine event(Instant time, String bkt, String cd, String message) {
        LoadedLogLine line = new LoadedLogLine(MsgType.JSON, null, 0, cd, bkt, time, "raw", time, time, message, false,
                Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("_cd", cd, "_bkt", bkt)));
        return line;
    }

    private static SearchSession newSession() {
        return new SearchSession("q", ORIGIN);
    }

    private static void addMatch(SearchSession session, LoadedLogLine line) {
        SearchSessionTestSupport.addMatch(session, SearchHit.from(line), line);
    }

    @Test
    @DisplayName("rowAtOrAfter finds the first event at or after a time, the last one past the end")
    void rowAtOrAfterByTime() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "bkt", "10:1", "single"));
        addMatch(session, event(at(20), "bkt", "20:1", "first\nsecond\nthird"));
        addMatch(session, event(at(30), "bkt", "30:1", "another"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.rowAtOrAfter(at(0))).hasValue(0);
        assertThat(provider.rowAtOrAfter(at(10))).hasValue(0);
        assertThat(provider.rowAtOrAfter(at(10).plusNanos(1))).as("first row of the multi-line event").hasValue(1);
        assertThat(provider.rowAtOrAfter(at(20))).hasValue(1);
        assertThat(provider.rowAtOrAfter(at(25))).hasValue(4);
        assertThat(provider.rowAtOrAfter(at(31))).as("past the end: the last event").hasValue(4);
    }

    @Test
    @DisplayName("rowAtOrAfter is empty without events")
    void rowAtOrAfterWithoutEvents() {
        assertThat(new MatchesDataProvider(newSession().snapshot()).rowAtOrAfter(at(0))).isEmpty();
    }

    @Test
    @DisplayName("row count covers all lines of multiline events and getLine carries line/continuation")
    void multilineRows() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "bkt", "10:1", "single"));
        addMatch(session, event(at(20), "bkt", "20:1", "first\nsecond\nthird"));
        addMatch(session, event(at(30), "bkt", "30:1", "another"));
        addMatch(session, event(at(40), "bkt", "40:1", "a\nb"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.rows().eventCount()).isEqualTo(4);
        assertThat(provider.rows().rowCount()).isEqualTo(7);

        LoadedLogLine first = (LoadedLogLine) provider.getLine(0, false);
        assertThat(first.getLineNumber()).isZero();
        assertThat(first.getMessage()).isEqualTo("single");
        assertThat(first.isContinuation()).isFalse();

        LoadedLogLine second = (LoadedLogLine) provider.getLine(2, false);
        assertThat(second.getLineNumber()).isEqualTo(2);
        assertThat(second.getMessage()).isEqualTo("second");
        assertThat(second.isContinuation()).isTrue();
    }

    @Test
    @DisplayName("out-of-range rows are placeholders while RUNNING and boundary lines once finished")
    void boundaryRowsAndPlaceholders() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "bkt", "10:1", "only"));
        SearchSessionTestSupport.coverWindow(session, at(0), at(60));

        MatchesDataProvider running = new MatchesDataProvider(session.snapshot());
        assertThat(running.getLine(-1, false)).isInstanceOf(Placeholder.class);
        assertThat(running.getLine(1, false)).isInstanceOf(Placeholder.class);

        SearchSessionTestSupport.complete(session);
        MatchesDataProvider done = new MatchesDataProvider(session.snapshot());

        assertThat(done.getLine(-1, false)).isInstanceOf(BeforeBoundaryLine.class);
        assertThat(done.getLine(1, false)).isInstanceOf(AfterBoundaryLine.class);

        assertThat(done.getBeforeBoundary().bt()).isEqualTo(BoundaryType.BEFORE);
        assertThat(done.getBeforeBoundary().instant()).isEqualTo(at(0));
        assertThat(done.getBeforeBoundary().line()).hasValue(0);
        assertThat(done.getAfterBoundary().bt()).isEqualTo(BoundaryType.AFTER);
        assertThat(done.getAfterBoundary().instant()).isEqualTo(at(60));
        assertThat(done.getAfterBoundary().line()).hasValue(1);
        assertThat(done.getMaxOobPerSide()).isEqualTo(2);
    }

    @Test
    @DisplayName("boundaries fall back to the first/last match time when no window is covered")
    void boundaryFallbackToMatchTimes() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "bkt", "10:1", "a"));
        addMatch(session, event(at(30), "bkt", "30:1", "b"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.getBeforeBoundary().instant()).isEqualTo(at(10));
        assertThat(provider.getBeforeBoundary().line()).hasValue(0);
        assertThat(provider.getAfterBoundary().instant()).isEqualTo(at(30));
        assertThat(provider.getAfterBoundary().line()).hasValue(2);
        assertThat(provider.jumpToLatest()).isEqualTo(1);
    }

    @Test
    @DisplayName("rowOfHit resolves exact identity even when two hits share a timestamp")
    void rowOfHitIdentity() {
        SearchSession session = newSession();
        LoadedLogLine first = event(at(10), "bkt-a", "10:1", "first");
        LoadedLogLine second = event(at(10), "bkt-a", "10:2", "second\nthird");
        addMatch(session, first);
        addMatch(session, second);

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.rowOfHit(SearchHit.from(first))).isZero();
        assertThat(provider.rowOfHit(SearchHit.from(second))).isEqualTo(1);

        // Same time, different _cd is a different event, not a match in this snapshot.
        SearchHit absent = new SearchHit(at(10), at(10), "bkt-a", "10:9");
        assertThat(provider.rowOfHit(absent)).isEqualTo(-1);

        assertThat(provider.hitAt(0)).isEqualTo(SearchHit.from(first));
        assertThat(provider.hitAt(1)).isEqualTo(SearchHit.from(second));
        assertThat(provider.hitAt(2)).isEqualTo(SearchHit.from(second));
        assertThat(provider.offsetWithin(2)).isEqualTo(1);

        assertThat(provider.jump(SearchHit.from(second))).isEqualTo(1);
    }

    @Test
    @DisplayName("jump(Instant) resolves sub-millisecond distances")
    void jumpSubMillisecond() {
        SearchSession session = newSession();
        addMatch(session, event(T0, "bkt", "1:1", "a"));
        addMatch(session, event(T0.plusNanos(100), "bkt", "1:2", "b"));
        addMatch(session, event(T0.plusNanos(300), "bkt", "1:3", "c"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.jump(T0.plusNanos(120))).isEqualTo(1); // 20 ns from b
        assertThat(provider.jump(T0.plusNanos(290))).isEqualTo(2); // 10 ns from c
    }

    @Test
    @DisplayName("jump(Instant) breaks an exact tie toward the earlier event")
    void jumpTie() {
        SearchSession session = newSession();
        addMatch(session, event(T0, "bkt", "1:1", "a"));
        addMatch(session, event(T0.plusNanos(200), "bkt", "1:2", "c"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.jump(T0.plusNanos(100))).isEqualTo(0); // 100 ns from both, earlier wins
    }

    @Test
    @DisplayName("refresh ignores an older or equal snapshot and applies a newer one")
    void refresh() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "bkt", "10:1", "one"));
        SearchSession.Snapshot first = session.snapshot();

        MatchesDataProvider provider = new MatchesDataProvider(first);
        assertThat(provider.rows().rowCount()).isEqualTo(1);
        assertThat(provider.snapshot()).isSameAs(first);

        addMatch(session, event(at(20), "bkt", "20:1", "two\nthree"));
        SearchSession.Snapshot second = session.snapshot();
        assertThat(second.version()).isGreaterThan(first.version());

        provider.refresh(second);
        assertThat(provider.snapshot()).isSameAs(second);
        assertThat(provider.rows().rowCount()).isEqualTo(3);

        provider.refresh(first);
        assertThat(provider.snapshot()).isSameAs(second);
        assertThat(provider.rows().rowCount()).isEqualTo(3);

        provider.refresh(second);
        assertThat(provider.snapshot()).isSameAs(second);
    }

    @Test
    @DisplayName("null lines (addHit-only entries) are skipped but their hits stay in the snapshot")
    void nullLinesSkipped() {
        SearchSession session = newSession();
        LoadedLogLine a = event(at(10), "bkt", "10:1", "a");
        addMatch(session, a);
        SearchHit nullLineHit = new SearchHit(at(20), at(20), "bkt", "20:1");
        SearchSessionTestSupport.addHit(session, nullLineHit);
        addMatch(session, event(at(30), "bkt", "30:1", "c"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.snapshot().size()).isEqualTo(3);
        assertThat(provider.rows().eventCount()).isEqualTo(2);
        assertThat(provider.rows().rowCount()).isEqualTo(2);

        assertThat(provider.rowOfHit(nullLineHit)).isEqualTo(-1);
        assertThat(provider.rowOfHit(SearchHit.from(a))).isZero();
    }

    @Test
    void streamingEdgesIncludeCoverageAndOnlyTheUnfinishedSideAnimates() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "b", "1", "match"));
        SearchSessionTestSupport.coverWindow(session, at(0), at(60));
        var snap = session.snapshot();
        var running = new MatchesDataProvider(snap);
        assertThat(((Placeholder) running.getLine(-1, false)).message())
                .isEqualTo("Searching earlier… covered back to " + DateTimeFormatterManager.formatTimestamp(at(0)));
        assertThat(((Placeholder) running.getLine(1, false)).message())
                .isEqualTo("Searching later… covered up to " + DateTimeFormatterManager.formatTimestamp(at(60)));
        var oneDone = new SearchSession.Snapshot(snap.version() + 1, snap.hits(), snap.lines(), snap.state(),
                snap.windowStart(), snap.windowEnd(), false, false, false, true, false, "q", ORIGIN, null);
        running.refresh(oneDone);
        assertThat(((BeforeBoundaryLine) running.getLine(-1, false)).message()).startsWith("Searched back to ");
        assertThat(running.getLine(1, false)).isInstanceOf(Placeholder.class);
    }

    @Test
    @DisplayName("only the row next to a running edge carries its message; the rest of the placeholders are blank")
    void onlyEdgeRowsCarryEdgeMessages() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "b", "1", "match"));
        SearchSessionTestSupport.coverWindow(session, at(0), at(60));
        var running = new MatchesDataProvider(session.snapshot());

        assertThat(((Placeholder) running.getLine(-2, false)).message()).isEmpty();
        assertThat(((Placeholder) running.getLine(-1, false)).message()).startsWith("Searching earlier… ");
        assertThat(((Placeholder) running.getLine(2, false)).message()).isEmpty();
        assertThat(((Placeholder) running.getLine(1, false)).message()).startsWith("Searching later… ");

        SearchSessionTestSupport.complete(session);
        var done = new MatchesDataProvider(session.snapshot());
        assertThat(((BeforeBoundaryLine) done.getLine(-2, false)).message()).isEmpty();
        assertThat(((BeforeBoundaryLine) done.getLine(-1, false)).message()).startsWith("Searched back to ");
        assertThat(((AfterBoundaryLine) done.getLine(2, false)).message()).isEmpty();
        assertThat(((AfterBoundaryLine) done.getLine(1, false)).message()).startsWith("Searched up to ");
    }

    @Test
    @DisplayName("with no rows yet, row 0 names the running side(s) in one message and nothing else is written")
    void emptyRunningSearchNamesBothSides() {
        SearchSession session = newSession();
        SearchSessionTestSupport.coverWindow(session, at(0), at(60));
        var both = new MatchesDataProvider(session.snapshot());
        assertThat(((Placeholder) both.getLine(0, false)).message()).isEqualTo("Searching… covered back to "
                + DateTimeFormatterManager.formatTimestamp(at(0)) + ", up to "
                + DateTimeFormatterManager.formatTimestamp(at(60)));
        assertThat(((Placeholder) both.getLine(-1, false)).message()).isEmpty();
        assertThat(((Placeholder) both.getLine(1, false)).message()).isEmpty();

        var snap = session.snapshot();
        var earlierOnly = new SearchSession.Snapshot(snap.version() + 1, snap.hits(), snap.lines(), snap.state(),
                snap.windowStart(), snap.windowEnd(), false, false, false, false, true, "q", ORIGIN, null);
        both.refresh(earlierOnly);
        assertThat(((Placeholder) both.getLine(0, false)).message()).isEqualTo("Searching earlier… covered back to "
                + DateTimeFormatterManager.formatTimestamp(at(0)));

        var laterOnly = new SearchSession.Snapshot(snap.version() + 2, snap.hits(), snap.lines(), snap.state(),
                snap.windowStart(), snap.windowEnd(), false, false, false, true, false, "q", ORIGIN, null);
        both.refresh(laterOnly);
        assertThat(((Placeholder) both.getLine(0, false)).message()).isEqualTo("Searching later… covered up to "
                + DateTimeFormatterManager.formatTimestamp(at(60)));
    }

    @Test
    void finishedEdgesExplainWindowAndAbsoluteLimits() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "b", "1", "match"));
        SearchSessionTestSupport.coverWindow(session, at(0), at(60));
        SearchSessionTestSupport.complete(session);
        var more = new MatchesDataProvider(session.snapshot());
        assertThat(((BeforeBoundaryLine) more.getLine(-1, false)).message()).startsWith("Searched back to ");
        assertThat(((AfterBoundaryLine) more.getLine(1, false)).message()).startsWith("Searched up to ");
        var limits = new MatchesDataProvider(session.snapshot(), at(0), at(60));
        assertThat(((BeforeBoundaryLine) limits.getLine(-1, false)).message())
                .isEqualTo("Start of searchable data");
        assertThat(((AfterBoundaryLine) limits.getLine(1, false)).message()).isEqualTo("Now");
    }

    @Test
    void stoppedAndTruncatedEdgesExplainWhyTheyEnded() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "b", "1", "match"));
        SearchSessionTestSupport.coverWindow(session, at(0), at(60));
        SearchSessionTestSupport.cancel(session);
        var stopped = new MatchesDataProvider(session.snapshot());
        assertThat(((BeforeBoundaryLine) stopped.getLine(-1, false)).message()).startsWith("Search stopped at ");
        assertThat(((AfterBoundaryLine) stopped.getLine(1, false)).message()).startsWith("Search stopped at ");
        SearchSessionTestSupport.markTruncated(session, SearchSession.Side.EARLIER);
        SearchSessionTestSupport.markTruncated(session, SearchSession.Side.LATER);
        SearchSessionTestSupport.complete(session);
        var truncated = new MatchesDataProvider(session.snapshot());
        assertThat(((BeforeBoundaryLine) truncated.getLine(-1, false)).message())
                .isEqualTo("Match limit (10,000) reached. Matches before "
                        + DateTimeFormatterManager.formatTimestamp(at(0)) + " are not shown.");
        assertThat(((AfterBoundaryLine) truncated.getLine(1, false)).message())
                .isEqualTo("Match limit (10,000) reached. Matches after "
                        + DateTimeFormatterManager.formatTimestamp(at(60)) + " are not shown.");
    }

    @Test
    void emptyAndFailedSearchesShowOneMessageRow() {
        SearchSession session = newSession();
        SearchSessionTestSupport.coverWindow(session, at(0), at(60));
        SearchSessionTestSupport.complete(session);
        var empty = new MatchesDataProvider(session.snapshot());
        assertThat(((AfterBoundaryLine) empty.getLine(0, false)).message()).startsWith("No matches for \"q\" between ")
                .contains(" and ");
        assertThat(((AfterBoundaryLine) empty.getLine(1, false)).message()).isEmpty();
        SearchSessionTestSupport.extendWith(session, SearchHit.at(at(10)));
        SearchSessionTestSupport.fail(session, "connection lost");
        var failed = new MatchesDataProvider(session.snapshot());
        assertThat(((AfterBoundaryLine) failed.getLine(0, false)).message())
                .isEqualTo("Search failed: connection lost");
    }

    @Test
    @DisplayName("gap and date rules sit on the first row of an event with a predecessor only")
    void rulesOnEventFirstRows() {
        SearchSession session = newSession();
        for (int seconds : new int[]{0, 10, 20, 30, 40, 50}) {
            addMatch(session, event(at(seconds), "bkt", seconds + ":1", "m" + seconds));
        }
        addMatch(session, event(at(650), "bkt", "650:1", "multi\nline"));
        addMatch(session, event(Instant.parse("2023-01-02T12:00:00Z"), "bkt", "day:1", "next day"));
        addMatch(session, event(Instant.parse("2023-01-02T12:00:05Z"), "bkt", "after:1", "five later"));
        SearchSessionTestSupport.complete(session);

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.gapThreshold()).contains(Duration.ofSeconds(80));
        assertThat(provider.rows().rowCount()).isEqualTo(10);
        assertThat(provider.ruleBefore(-1)).isInstanceOf(GapRule.None.class);
        assertThat(provider.ruleBefore(0)).isEqualTo(new GapRule.Date(LocalDate.of(2023, 1, 1)));
        assertThat(provider.ruleBefore(1)).isInstanceOf(GapRule.None.class);
        assertThat(provider.ruleBefore(6)).isEqualTo(new GapRule.Gap(Duration.ofSeconds(600)));
        assertThat(provider.ruleBefore(7)).isInstanceOf(GapRule.None.class);
        assertThat(provider.ruleBefore(8)).isEqualTo(new GapRule.Date(LocalDate.of(2023, 1, 2)));
        assertThat(provider.ruleBefore(9)).isInstanceOf(GapRule.None.class);
        assertThat(provider.ruleBefore(10)).isInstanceOf(GapRule.None.class);
    }

    @Test
    @DisplayName("the first event of the list is dated even when it is the only event")
    void firstEventIsDated() {
        SearchSession session = newSession();
        addMatch(session, event(at(10), "bkt", "10:1", "only"));
        SearchSessionTestSupport.complete(session);

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.ruleBefore(0)).isEqualTo(new GapRule.Date(LocalDate.of(2023, 1, 1)));
    }

    @Test
    @DisplayName("the gap threshold does not move while a running search grows by less than 10 %")
    void thresholdStableWhileGrowing() {
        SearchSession session = newSession();
        for (int i = 0; i < 20; i++) {
            addMatch(session, event(at(i * 10), "bkt", i + ":1", "m" + i));
        }
        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());
        var first = provider.gapThreshold();
        assertThat(first).contains(Duration.ofSeconds(80));

        addMatch(session, event(at(190 + 1000), "bkt", "x:1", "late"));
        provider.refresh(session.snapshot());

        assertThat(provider.gapThreshold()).isEqualTo(first);
    }

    @Test
    @DisplayName("field-match suffix only on the first row of events whose message lacks the term")
    void fieldMatchSuffix() {
        SearchSession session = new SearchSession("error", ORIGIN);
        addMatch(session, event(at(10), "bkt", "10:1", "disk error"));
        addMatch(session, event(at(20), "bkt", "20:1", "first line\nerror on second"));
        addMatch(session, event(at(30), "bkt", "30:1", "nothing shown here"));
        addMatch(session, event(at(40), "bkt", "40:1", "two\nlines without"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.suffixAt(0)).isNull();
        assertThat(provider.suffixAt(1)).isNull();
        assertThat(provider.suffixAt(2)).isNull();
        assertThat(provider.suffixAt(3)).isEqualTo("(match in fields)");
        assertThat(provider.suffixAt(4)).isEqualTo("(match in fields)");
        assertThat(provider.suffixAt(5)).isNull();
    }

    @Test
    @DisplayName("first-line mode: one row per event, hidden line count as ⏎+N suffix")
    void firstLineModeShowsOneRowPerEvent() {
        SearchSession session = new SearchSession("", ORIGIN);
        addMatch(session, event(at(10), "bkt", "10:1", "single"));
        addMatch(session, event(at(20), "bkt", "20:1", "first\nsecond\nthird"));
        addMatch(session, event(at(30), "bkt", "30:1", "another"));
        addMatch(session, event(at(40), "bkt", "40:1", "a\nb"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot(),
                se.grynna.pager.splunk.SearchBoundaries.MIN_MINIMUM_BOUNDARY, Instant.MAX, true);

        assertThat(provider.firstLineOnly()).isTrue();
        assertThat(provider.rows().rowCount()).isEqualTo(provider.rows().eventCount()).isEqualTo(4);
        assertThat(provider.suffixAt(0)).isNull();
        assertThat(provider.suffixAt(1)).isEqualTo("⏎+2");
        assertThat(provider.suffixAt(2)).isNull();
        assertThat(provider.suffixAt(3)).isEqualTo("⏎+1");

        LoadedLogLine shown = (LoadedLogLine) provider.getLine(1, false);
        assertThat(shown.getMessage()).isEqualTo("first");
        assertThat(shown.isContinuation()).isFalse();
        assertThat(provider.offsetWithin(1)).isZero();
        assertThat(provider.hitAt(1).splunkTime()).isEqualTo(at(20));
    }

    @Test
    @DisplayName("first-line mode: field-match suffix combines with the ⏎+N suffix")
    void firstLineModeCombinesHiddenCountAndFieldMatch() {
        SearchSession session = new SearchSession("error", ORIGIN);
        addMatch(session, event(at(10), "bkt", "10:1", "disk error"));
        addMatch(session, event(at(20), "bkt", "20:1", "nothing here\nthird line"));
        addMatch(session, event(at(30), "bkt", "30:1", "still nothing\nerror on second"));

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot(),
                se.grynna.pager.splunk.SearchBoundaries.MIN_MINIMUM_BOUNDARY, Instant.MAX, true);

        assertThat(provider.suffixAt(0)).isNull();
        assertThat(provider.suffixAt(1)).isEqualTo("⏎+1 (match in fields)");
        assertThat(provider.suffixAt(2)).isEqualTo("⏎+1");
    }

    @Test
    @DisplayName("first-line mode: a refresh keeps the mode")
    void firstLineModeSurvivesRefresh() {
        SearchSession session = new SearchSession("", ORIGIN);
        addMatch(session, event(at(10), "bkt", "10:1", "first\nsecond"));
        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot(),
                se.grynna.pager.splunk.SearchBoundaries.MIN_MINIMUM_BOUNDARY, Instant.MAX, true);

        addMatch(session, event(at(20), "bkt", "20:1", "x\ny\nz"));
        provider.refresh(session.snapshot());

        assertThat(provider.firstLineOnly()).isTrue();
        assertThat(provider.rows().rowCount()).isEqualTo(2);
        assertThat(provider.suffixAt(1)).isEqualTo("⏎+2");
    }

    @Test
    @DisplayName("whole-events mode keeps every line and never shows ⏎+N")
    void wholeEventsModeUnchanged() {
        SearchSession session = new SearchSession("", ORIGIN);
        addMatch(session, event(at(10), "bkt", "10:1", "first\nsecond\nthird"));
        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(provider.firstLineOnly()).isFalse();
        assertThat(provider.rows().rowCount()).isEqualTo(3);
        assertThat(provider.rows().hiddenLines(0)).isZero();
        assertThat(provider.suffixAt(0)).isNull();
    }

    @Test
    @DisplayName("selected text never contains the field-match suffix")
    void suffixIsNotCopied() {
        SearchSession session = new SearchSession("error", ORIGIN);
        addMatch(session, event(at(10), "bkt", "10:1", "nothing shown here"));
        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        DecoratedLogLine line = new DecoratedLogLine((LoadedLogLine) provider.getLine(0, false));
        line.setSuffix(provider.suffixAt(0));
        line.setSelectionStart(0);
        line.setSelectionEnd(line.formattedLine().length());

        assertThat(line.getSuffix()).isEqualTo("(match in fields)");
        assertThat(line.selected()).isEqualTo(line.formattedLine()).contains("nothing shown here")
                .doesNotContain("match in fields");
    }
}
