package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession.Snapshot;
import se.grynna.pager.splunk.SearchSession.State;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRefinementTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");

    private static LoadedLogLine line(int second, String raw, String message) {
        Instant time = T0.plusSeconds(second);
        return new LoadedLogLine(MsgType.RAW, "seg", second, "cd" + second, "bkt", time, raw, time, time, message,
                false, Level.INFO);
    }

    private static LoadedLogLine line(int second, String raw) {
        return line(second, raw, "msg " + second);
    }

    private static Snapshot snapshot(State state, Instant windowStart, Instant windowEnd, LoadedLogLine... lines) {
        List<SearchHit> hits = new ArrayList<>();
        for (LoadedLogLine line : lines) {
            hits.add(SearchHit.from(line));
        }
        return new Snapshot(7, hits, List.of(lines), state, windowStart, windowEnd, false, false, false);
    }

    private static SearchRefinement.Provisional provisionalOf(LoadedLogLine... lines) {
        return SearchRefinement.provisional(snapshot(State.COMPLETED, null, null, lines), "x");
    }

    private static List<Instant> times(Snapshot snapshot) {
        return snapshot.hits().stream().map(SearchHit::splunkTime).toList();
    }

    // === refinement decision ===

    @Test
    void aLongerPlainTermContainingTheOldOneIsARefinement() {
        assertThat(SearchRefinement.isRefinement("time", "timeout")).isTrue();
        assertThat(SearchRefinement.isRefinement("time", "a time")).isTrue();
        assertThat(SearchRefinement.isRefinement("db error", "db error 500")).isTrue();
        assertThat(SearchRefinement.isRefinement("  time ", "timeout")).isTrue();
    }

    @Test
    void containmentIsCaseInsensitive() {
        assertThat(SearchRefinement.isRefinement("TIME", "timeout")).isTrue();
        assertThat(SearchRefinement.isRefinement("time", "TimeOut")).isTrue();
    }

    @Test
    void theSameTermIsNotARefinementNorAnUnrelatedOne() {
        assertThat(SearchRefinement.isRefinement("time", "time")).isFalse();
        assertThat(SearchRefinement.isRefinement("time", "TIME")).isFalse();
        assertThat(SearchRefinement.isRefinement("timeout", "time")).isFalse();
        assertThat(SearchRefinement.isRefinement("time", "error")).isFalse();
    }

    @Test
    void wildcardsQuotesAndBooleanOperatorsAreNeverRefinements() {
        assertThat(SearchRefinement.isRefinement("time*", "time*out")).isFalse();
        assertThat(SearchRefinement.isRefinement("time", "time*")).isFalse();
        assertThat(SearchRefinement.isRefinement("\"time\"", "\"timeout\"")).isFalse();
        assertThat(SearchRefinement.isRefinement("time", "time \"out\"")).isFalse();
        assertThat(SearchRefinement.isRefinement("time", "time OR error")).isFalse();
        assertThat(SearchRefinement.isRefinement("time", "time and error")).isFalse();
        assertThat(SearchRefinement.isRefinement("time", "time Not error")).isFalse();
        assertThat(SearchRefinement.isRefinement("a OR b", "a OR b c")).isFalse();
    }

    @Test
    void operatorWordsInsideAnotherWordAreStillPlain() {
        assertThat(SearchRefinement.isPlain("order android notable")).isTrue();
        assertThat(SearchRefinement.isRefinement("order", "orderly")).isTrue();
    }

    @Test
    void blankOrNullTermsAreNotPlain() {
        assertThat(SearchRefinement.isPlain(null)).isFalse();
        assertThat(SearchRefinement.isPlain("  ")).isFalse();
        assertThat(SearchRefinement.isRefinement(null, "x")).isFalse();
        assertThat(SearchRefinement.isRefinement("x", null)).isFalse();
    }

    // === local test ===

    @Test
    void theLocalTestIsACaseInsensitiveSubstringOfRaw() {
        LoadedLogLine line = line(0, "GET /Login timeout=30", "ignored");
        assertThat(SearchRefinement.matches(line, "login out")).isFalse();
        assertThat(SearchRefinement.matches(line, "/login time")).isTrue();
        assertThat(SearchRefinement.matches(line, "ignored")).isFalse();
    }

    @Test
    void theMessageIsTestedWhenRawIsBlank() {
        assertThat(SearchRefinement.matches(line(0, "", "Hello World"), "o w")).isTrue();
        assertThat(SearchRefinement.matches(line(0, "  ", "Hello World"), "hello")).isTrue();
        assertThat(SearchRefinement.matches(line(0, null, "Hello World"), "hello")).isTrue();
        assertThat(SearchRefinement.matches(line(0, "", "Hello World"), "bye")).isFalse();
        assertThat(SearchRefinement.matches(null, "x")).isFalse();
    }

    @Test
    void theProvisionalSetIsASupersetWhereSplunkTokenisesDifferently() {
        // Splunk's "search time" does not match "timeout" (tokenised); the substring test does.
        LoadedLogLine tokenMatch = line(0, "slow search time 3s");
        LoadedLogLine substringOnly = line(10, "search timeout after 3s");
        LoadedLogLine neither = line(20, "search finished");
        Snapshot old = snapshot(State.COMPLETED, null, null, tokenMatch, substringOnly, neither);

        SearchRefinement.Provisional provisional = SearchRefinement.provisional(old, "search time");

        assertThat(provisional.hits()).containsExactly(SearchHit.from(tokenMatch), SearchHit.from(substringOnly));
        assertThat(provisional.lines()).containsExactly(tokenMatch, substringOnly);
    }

    @Test
    void everyOldMatchThatContainsTheNewTermIsKept() {
        // Never misses a confirmed match that was in the old snapshot.
        List<LoadedLogLine> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(line(i, i % 3 == 0 ? "alpha beta " + i : "alpha " + i));
        }
        Snapshot old = snapshot(State.COMPLETED, null, null, lines.toArray(new LoadedLogLine[0]));

        SearchRefinement.Provisional provisional = SearchRefinement.provisional(old, "alpha beta");

        assertThat(provisional.size()).isEqualTo(7);
        assertThat(provisional.lines()).allMatch(l -> l.getRaw().contains("alpha beta"));
    }

    @Test
    void hitsWithoutAMappedLineCannotBeTested() {
        SearchHit bare = SearchHit.at(T0);
        Snapshot old = new Snapshot(1, List.of(bare), java.util.Collections.singletonList(null), State.COMPLETED, null,
                null, false, false, false);
        assertThat(SearchRefinement.provisional(old, "x").size()).isZero();
    }

    // === merge rule ===

    @Test
    void provisionalHitsInsideTheCoveredWindowAreDropped() {
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(20, "x"), line(30, "x"));
        Snapshot confirmed = snapshot(State.RUNNING, T0.plusSeconds(15), T0.plusSeconds(25));

        SearchRefinement.Merged merged = SearchRefinement.merge(confirmed, provisional);

        assertThat(times(merged.snapshot())).containsExactly(T0.plusSeconds(10), T0.plusSeconds(30));
        assertThat(merged.provisionalCount()).isEqualTo(2);
    }

    @Test
    void provisionalHitsOutsideTheWindowStandInAndConfirmedOnesAreAllKept() {
        LoadedLogLine confirmedLine = line(20, "x");
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(40, "x"));
        Snapshot confirmed = snapshot(State.RUNNING, T0.plusSeconds(15), T0.plusSeconds(25), confirmedLine);

        SearchRefinement.Merged merged = SearchRefinement.merge(confirmed, provisional);

        assertThat(times(merged.snapshot())).containsExactly(T0.plusSeconds(10), T0.plusSeconds(20),
                T0.plusSeconds(40));
        assertThat(merged.provisionalCount()).isEqualTo(2);
        assertThat(merged.snapshot().lines().get(1)).isSameAs(confirmedLine);
    }

    @Test
    void theCoveredWindowIsHalfOpen() {
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(20, "x"));
        Snapshot confirmed = snapshot(State.RUNNING, T0.plusSeconds(10), T0.plusSeconds(20));

        SearchRefinement.Merged merged = SearchRefinement.merge(confirmed, provisional);

        assertThat(times(merged.snapshot())).containsExactly(T0.plusSeconds(20));
    }

    @Test
    void withNoCoverageYetEveryProvisionalHitStandsIn() {
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(20, "x"));

        SearchRefinement.Merged merged = SearchRefinement.merge(snapshot(State.RUNNING, null, null), provisional);

        assertThat(merged.snapshot().size()).isEqualTo(2);
        assertThat(merged.provisionalCount()).isEqualTo(2);
    }

    @Test
    void aCompletedSessionDropsAllProvisionalHits() {
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(40, "x"));
        Snapshot confirmed = snapshot(State.COMPLETED, T0.plusSeconds(15), T0.plusSeconds(25), line(20, "x"));

        SearchRefinement.Merged merged = SearchRefinement.merge(confirmed, provisional);

        assertThat(merged.snapshot()).isSameAs(confirmed);
        assertThat(merged.provisionalCount()).isZero();
    }

    @Test
    void aCancelledOrFailedSessionShowsOnlyWhatWasConfirmed() {
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(40, "x"));
        Snapshot cancelled = snapshot(State.CANCELLED, T0.plusSeconds(15), T0.plusSeconds(25), line(20, "x"));
        Snapshot failed = snapshot(State.ERROR, null, null);

        assertThat(times(SearchRefinement.merge(cancelled, provisional).snapshot())).containsExactly(
                T0.plusSeconds(20));
        assertThat(SearchRefinement.merge(failed, provisional).snapshot().size()).isZero();
        assertThat(SearchRefinement.merge(failed, provisional).provisionalCount()).isZero();
    }

    @Test
    void aProvisionalHitAlreadyConfirmedIsNotDuplicated() {
        LoadedLogLine confirmedLine = line(10, "x confirmed");
        // Same identity, even though it sits outside the covered window
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(40, "x"));
        Snapshot confirmed = snapshot(State.RUNNING, T0.plusSeconds(50), T0.plusSeconds(60), confirmedLine);

        SearchRefinement.Merged merged = SearchRefinement.merge(confirmed, provisional);

        assertThat(times(merged.snapshot())).containsExactly(T0.plusSeconds(10), T0.plusSeconds(40));
        assertThat(merged.snapshot().lines().getFirst()).isSameAs(confirmedLine);
        assertThat(merged.provisionalCount()).isEqualTo(1);
    }

    @Test
    void equalTimestampsAreToldApartByIdentityAndStayOrdered() {
        Instant time = T0.plusSeconds(10);
        LoadedLogLine a = new LoadedLogLine(MsgType.RAW, "seg", 0, "1", "bkt", time, "x", time, time, "m", false,
                Level.INFO);
        LoadedLogLine b = new LoadedLogLine(MsgType.RAW, "seg", 0, "2", "bkt", time, "x", time, time, "m", false,
                Level.INFO);
        SearchRefinement.Provisional provisional = provisionalOf(a, b);
        Snapshot confirmed = snapshot(State.RUNNING, null, null, b);

        SearchRefinement.Merged merged = SearchRefinement.merge(confirmed, provisional);

        assertThat(merged.snapshot().hits()).containsExactly(SearchHit.from(a), SearchHit.from(b));
        assertThat(merged.provisionalCount()).isEqualTo(1);
    }

    @Test
    void theMergedSnapshotKeepsTheConfirmedVersionWindowAndFlags() {
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"));
        Snapshot confirmed = new Snapshot(42, List.of(), List.of(), State.RUNNING, T0.plusSeconds(50),
                T0.plusSeconds(60), true, false, false, false, true, "q", T0, null);

        Snapshot merged = SearchRefinement.merge(confirmed, provisional).snapshot();

        assertThat(merged.version()).isEqualTo(confirmed.version());
        assertThat(merged.windowStart()).isEqualTo(confirmed.windowStart());
        assertThat(merged.windowEnd()).isEqualTo(confirmed.windowEnd());
        assertThat(merged.truncatedEarlier()).isTrue();
        assertThat(merged.laterDone()).isTrue();
        assertThat(merged.query()).isEqualTo("q");
        assertThat(merged.state()).isEqualTo(State.RUNNING);
    }

    @Test
    void theMergeFollowsEveryConfirmedChangeSoTheVersionIsEnough() {
        // The remainder is a function of the confirmed snapshot only: widening the window
        // (a version bump of the confirmed snapshot) shrinks it, so a version-keyed cache refreshes.
        SearchRefinement.Provisional provisional = provisionalOf(line(10, "x"), line(40, "x"));
        Snapshot before = snapshot(State.RUNNING, T0.plusSeconds(30), T0.plusSeconds(35));
        Snapshot after = new Snapshot(8, before.hits(), before.lines(), State.RUNNING, T0, T0.plusSeconds(35), false,
                false, false);

        assertThat(SearchRefinement.merge(before, provisional).snapshot().size()).isEqualTo(2);
        assertThat(SearchRefinement.merge(after, provisional).snapshot().size()).isEqualTo(1);
        assertThat(SearchRefinement.merge(after, provisional).snapshot().version())
                .isGreaterThan(SearchRefinement.merge(before, provisional).snapshot().version());
    }

    @Test
    void anEmptyProvisionalListLeavesTheSnapshotUntouched() {
        Snapshot confirmed = snapshot(State.RUNNING, null, null, line(10, "x"));

        assertThat(SearchRefinement.merge(confirmed, SearchRefinement.Provisional.EMPTY).snapshot())
                .isSameAs(confirmed);
        assertThat(SearchRefinement.merge(confirmed, null).snapshot()).isSameAs(confirmed);
    }
}
