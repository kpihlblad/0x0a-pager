package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SearchSessionTest {

    private static final Instant T0 = Instant.parse("2024-03-30T10:00:00Z");

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private SearchSession sessionWithHits(int... seconds) {
        SearchSession session = new SearchSession("boom", at(0));
        for (int s : seconds) {
            session.addHit(at(s));
        }
        return session;
    }

    private static SearchHit hit(int seconds, String bkt, String cd) {
        Instant t = at(seconds);
        return new SearchHit(t, t, bkt, cd);
    }

    @Test
    void startsRunningWithNoHits() {
        SearchSession session = new SearchSession("boom", T0);

        assertEquals(SearchSession.State.RUNNING, session.getState());
        assertEquals(0, session.hitCount());
        assertTrue(session.firstHitTimestamp().isEmpty());
        assertTrue(session.lastHitTimestamp().isEmpty());
        assertEquals("boom", session.getQuery());
        assertEquals(T0, session.getSearchOrigin());
    }

    @Test
    void hitsAreDeduplicatedAndSortedChronologically() {
        SearchSession session = sessionWithHits(30, 10, 20, 10);

        assertEquals(3, session.hitCount());
        assertEquals(List.of(at(10), at(20), at(30)), session.getHits().stream().map(SearchHit::splunkTime).toList());
        assertEquals(at(10), session.firstHitTimestamp().orElseThrow());
        assertEquals(at(30), session.lastHitTimestamp().orElseThrow());
    }

    @Test
    void equalSplunkTimeHitsWithDifferentIdentityAreKeptInDeterministicOrder() {
        SearchSession session = new SearchSession("boom", at(0));
        session.addHit(hit(10, "bkt2", "10:2"));
        session.addHit(hit(10, "bkt1", "10:1"));

        assertEquals(2, session.hitCount());
        var hits = session.getHits();
        assertEquals("10:1", hits.getFirst().cd());
        assertEquals("10:2", hits.getLast().cd());
    }

    @Test
    void nextHitIsStrictlyAfterTheReferencePoint() {
        SearchSession session = sessionWithHits(10, 20, 30);

        assertEquals(at(10), session.nextHit(at(0)).orElseThrow());
        assertEquals(at(20), session.nextHit(at(10)).orElseThrow(), "must not return the reference itself");
        assertEquals(at(30), session.nextHit(at(25)).orElseThrow());
        assertTrue(session.nextHit(at(30)).isEmpty(), "no hit after the last one");
    }

    @Test
    void previousHitIsStrictlyBeforeTheReferencePoint() {
        SearchSession session = sessionWithHits(10, 20, 30);

        assertEquals(at(30), session.previousHit(at(40)).orElseThrow());
        assertEquals(at(20), session.previousHit(at(30)).orElseThrow(), "must not return the reference itself");
        assertEquals(at(10), session.previousHit(at(15)).orElseThrow());
        assertTrue(session.previousHit(at(10)).isEmpty(), "no hit before the first one");
    }

    @Test
    void closestHitPicksTheNearerNeighbourAndFallsBackAtTheEdges() {
        SearchSession session = sessionWithHits(10, 30);

        assertEquals(at(10), session.closestHit(at(14)).orElseThrow());
        assertEquals(at(30), session.closestHit(at(26)).orElseThrow());
        // Exactly halfway — ties resolve forward
        assertEquals(at(30), session.closestHit(at(20)).orElseThrow());
        // Outside the range on both sides
        assertEquals(at(10), session.closestHit(at(0)).orElseThrow());
        assertEquals(at(30), session.closestHit(at(99)).orElseThrow());

        assertTrue(new SearchSession("q", T0).closestHit(T0).isEmpty());
    }

    @Test
    void closestHitPreservesNanosecondPrecisionAndReturnsExactHit() {
        SearchSession session = new SearchSession("boom", T0);
        session.addHit(T0.plusNanos(100));
        session.addHit(T0.plusNanos(200));
        session.addHit(T0.plusNanos(300));

        assertEquals(T0.plusNanos(300), session.closestHit(T0.plusNanos(300)).orElseThrow());
        assertEquals(T0.plusNanos(300), session.closestHit(T0.plusNanos(260)).orElseThrow());
        assertEquals(T0.plusNanos(200), session.closestHit(T0.plusNanos(240)).orElseThrow());
    }

    @Test
    void getHitsReturnsAnImmutableSnapshot() {
        SearchSession session = sessionWithHits(10);
        List<SearchHit> snapshot = session.getHits();

        session.addHit(at(20));

        assertEquals(1, snapshot.size(), "snapshot must not observe later hits");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(SearchHit.at(at(99))));
    }

    @Test
    void stateTransitionsNotifyListeners() {
        SearchSession session = new SearchSession("boom", T0);
        List<SearchSession.State> observed = new ArrayList<>();
        session.addUpdateListener(s -> observed.add(s.getState()));

        session.setState(SearchSession.State.COMPLETED);

        assertEquals(List.of(SearchSession.State.COMPLETED), observed);
        assertEquals(SearchSession.State.COMPLETED, session.getState());
    }

    @Test
    void cancelMovesToCancelledAndNotifies() {
        SearchSession session = new SearchSession("boom", T0);
        AtomicInteger notifications = new AtomicInteger();
        session.addUpdateListener(s -> notifications.incrementAndGet());

        session.cancel();

        assertEquals(SearchSession.State.CANCELLED, session.getState());
        assertEquals(1, notifications.get());
    }

    @Test
    void setErrorRecordsMessageAndMovesToErrorState() {
        SearchSession session = new SearchSession("boom", T0);
        AtomicInteger notifications = new AtomicInteger();
        session.addUpdateListener(s -> notifications.incrementAndGet());

        session.setError("splunk exploded");

        assertEquals(SearchSession.State.ERROR, session.getState());
        assertEquals("splunk exploded", session.getErrorMessage());
        assertEquals(1, notifications.get());
    }

    @Test
    void firstHitNotifiesListenersImmediately() {
        SearchSession session = new SearchSession("boom", T0);
        AtomicInteger notifications = new AtomicInteger();
        session.addUpdateListener(s -> notifications.incrementAndGet());

        session.addHit(at(1));

        assertEquals(1, notifications.get(), "the UI must learn about the first hit without waiting");
    }

    @Test
    void rapidHitsAreThrottledButAllAreRecorded() {
        SearchSession session = new SearchSession("boom", T0);
        AtomicInteger notifications = new AtomicInteger();
        session.addUpdateListener(s -> notifications.incrementAndGet());

        for (int i = 0; i < 1000; i++) {
            session.addHit(at(i));
        }

        assertEquals(1000, session.hitCount(), "every hit must be recorded");
        assertTrue(notifications.get() < 100,
                "throttling must collapse 1000 hits into few notifications, got " + notifications.get());
    }

    @Test
    void removedListenerStopsReceivingUpdates() {
        SearchSession session = new SearchSession("boom", T0);
        AtomicInteger notifications = new AtomicInteger();
        java.util.function.Consumer<SearchSession> listener = s -> notifications.incrementAndGet();
        session.addUpdateListener(listener);
        session.removeUpdateListener(listener);

        session.setState(SearchSession.State.COMPLETED);

        assertEquals(0, notifications.get());
    }

    @Test
    void staleRunCannotAddHitsOrEndTheCurrentRun() {
        SearchSession session = new SearchSession("boom", T0);
        long stopped = session.beginRun();
        session.addHit(stopped, SearchHit.at(at(1)));
        assertTrue(session.cancel(stopped));

        long extension = session.beginRun();
        assertEquals(SearchSession.State.RUNNING, session.getState());

        session.addHit(stopped, SearchHit.at(at(2)));
        assertFalse(session.complete(stopped), "a superseded run must not complete the session");
        assertFalse(session.fail(stopped, "interrupted"), "a superseded run must not fail the session");
        assertFalse(session.cancel(stopped));
        assertEquals(SearchSession.State.RUNNING, session.getState());
        assertEquals(1, session.hitCount(), "hit from the superseded run must be ignored");

        session.addHit(extension, SearchHit.at(at(3)));
        assertTrue(session.complete(extension));
        assertEquals(SearchSession.State.COMPLETED, session.getState());
        assertEquals(2, session.hitCount());
    }

    @Test
    void finishedRunIgnoresLateTransitionsAndHits() {
        SearchSession session = new SearchSession("boom", T0);
        long run = session.beginRun();
        assertTrue(session.complete(run));

        assertFalse(session.cancel(run), "cancel must not overwrite COMPLETED");
        assertFalse(session.fail(run, "late"), "a late error must not overwrite COMPLETED");
        session.addHit(run, SearchHit.at(at(1)));

        assertEquals(SearchSession.State.COMPLETED, session.getState());
        assertNull(session.getErrorMessage());
        assertEquals(0, session.hitCount());
    }

    @Test
    void completingWithWindowCoversItBeforeListenersHearAboutIt() {
        SearchSession session = new SearchSession("boom", T0);
        List<Instant> seenEnd = new ArrayList<>();
        session.addUpdateListener(s -> seenEnd.add(s.windowEnd()));
        long run = session.beginRun();

        assertTrue(session.complete(run, at(0), at(60)));

        assertEquals(List.of(at(60)), seenEnd);
    }

    @Test
    void discardCancelsDetachesListenersAndRefusesNewRuns() {
        SearchSession session = new SearchSession("boom", T0);
        long run = session.beginRun();
        AtomicInteger notifications = new AtomicInteger();
        session.addUpdateListener(s -> notifications.incrementAndGet());

        session.discard();

        assertTrue(session.isDiscarded());
        assertEquals(SearchSession.State.CANCELLED, session.getState());
        assertEquals(1, notifications.get(), "listeners hear about the cancellation once");

        session.addHit(run, SearchHit.at(at(1)));
        long revived = session.beginRun();
        assertFalse(session.isActiveRun(revived));
        assertFalse(session.complete(revived));
        assertEquals(SearchSession.State.CANCELLED, session.getState());
        assertEquals(0, session.hitCount());
        assertEquals(1, notifications.get(), "a discarded session no longer notifies");
    }

    @Test
    void aThrowingListenerDoesNotBreakOtherListeners() {
        SearchSession session = new SearchSession("boom", T0);
        AtomicInteger reached = new AtomicInteger();
        session.addUpdateListener(s -> {
            throw new IllegalStateException("listener blew up");
        });
        session.addUpdateListener(s -> reached.incrementAndGet());

        assertDoesNotThrow(() -> session.setState(SearchSession.State.COMPLETED));
        assertEquals(1, reached.get());
    }

    @Test
    void snapshotIsCachedUntilVersionChanges() {
        SearchSession session = new SearchSession("boom", T0);
        long v0 = session.version();
        SearchSession.Snapshot first = session.snapshot();

        assertSame(first, session.snapshot(), "unchanged version must reuse the snapshot instance");
        assertEquals(v0, first.version());
        assertEquals(0, first.size());

        session.addHit(at(1));
        assertTrue(session.version() > v0, "a successful add bumps version");
        SearchSession.Snapshot afterAdd = session.snapshot();
        assertNotSame(first, afterAdd);
        assertSame(afterAdd, session.snapshot());
        assertEquals(1, afterAdd.size());
        assertEquals(session.version(), afterAdd.version());

        long afterDuplicate = session.version();
        session.addHit(at(1));
        assertEquals(afterDuplicate, session.version(), "a duplicate add is not a change");
        assertSame(afterAdd, session.snapshot());

        long beforeComplete = session.version();
        session.setState(SearchSession.State.COMPLETED);
        assertTrue(session.version() > beforeComplete, "a state change bumps version");
        SearchSession.Snapshot afterComplete = session.snapshot();
        assertNotSame(afterAdd, afterComplete);
        assertEquals(SearchSession.State.COMPLETED, afterComplete.state());
        assertSame(afterComplete, session.snapshot());
    }

    @Test
    void versionIncreasesOnTerminalTransitionsAndDiscard() {
        SearchSession completed = new SearchSession("boom", T0);
        long v = completed.version();
        completed.setState(SearchSession.State.COMPLETED);
        assertTrue(completed.version() > v);

        SearchSession cancelled = new SearchSession("boom", T0);
        v = cancelled.version();
        cancelled.cancel();
        assertTrue(cancelled.version() > v);
        long stillCancelled = cancelled.version();
        cancelled.beginRun();
        assertTrue(cancelled.version() > stillCancelled, "beginRun bumps version when it leaves a terminal state");

        SearchSession failed = new SearchSession("boom", T0);
        v = failed.version();
        failed.setError("x");
        assertTrue(failed.version() > v);

        SearchSession discarded = new SearchSession("boom", T0);
        v = discarded.version();
        discarded.discard();
        assertTrue(discarded.version() > v);
        long afterDiscard = discarded.version();
        discarded.discard();
        assertEquals(afterDiscard, discarded.version(), "a second discard is a no-op");
    }

    @Test
    void indexOfAndCeilingIndexDistinguishIdentitiesThatShareATimestamp() {
        SearchSession session = new SearchSession("boom", T0);
        Instant t = at(10);
        SearchHit first = new SearchHit(t, t, "bkt-a", "10:1");
        SearchHit second = new SearchHit(t, t, "bkt-b", "10:2");
        SearchHit third = new SearchHit(t, t, "bkt-a", "10:3");
        session.addHit(third);
        session.addHit(first);
        session.addHit(second);

        SearchSession.Snapshot snap = session.snapshot();
        assertEquals(0, snap.indexOf(first));
        assertEquals(1, snap.indexOf(second));
        assertEquals(2, snap.indexOf(third));
        assertEquals(0, session.indexOf(first));
        assertEquals(2, session.indexOf(third));

        SearchHit absentCd = new SearchHit(t, t, "bkt-a", "10:9");
        SearchHit absentBkt = new SearchHit(t, t, "bkt-z", "10:1");
        assertEquals(-1, snap.indexOf(absentCd), "same time, different _cd is not a match");
        assertEquals(-1, session.indexOf(absentBkt), "same time and _cd numbers, different _bkt is not a match");
        assertEquals(-1, snap.indexOf(null));

        // Same parsed _cd and timestamp as `first`, but a _bkt that sorts between first and second.
        SearchHit between = new SearchHit(t, t, "bkt-m", "10:1");
        assertEquals(-1, snap.indexOf(between));
        assertEquals(1, snap.ceilingIndex(between));
        assertEquals(0, snap.ceilingIndex(first), "an exact identity is its own ceiling");
        assertEquals(0, snap.ceilingIndex(new SearchHit(at(9), at(9), "zzz", "99:9")));
        assertEquals(snap.size(), snap.ceilingIndex(new SearchHit(at(11), at(11), null, null)));
    }

    @Test
    void snapshotKeepsLinesAlignedWithHitsAndIgnoresStaleAddMatch() {
        SearchSession session = new SearchSession("boom", T0);
        long run = session.beginRun();
        LoadedLogLine later = line(30, "bkt", "30:1", "later");
        LoadedLogLine earlier = line(10, "bkt", "10:1", "earlier");
        session.addMatch(run, SearchHit.from(later), later);
        session.addMatch(run, SearchHit.from(earlier), earlier);

        SearchSession.Snapshot snap = session.snapshot();
        assertEquals(List.of(SearchHit.from(earlier), SearchHit.from(later)), snap.hits());
        assertEquals(2, snap.lines().size());
        assertSame(earlier, snap.lines().get(0));
        assertSame(later, snap.lines().get(1));
        assertSame(earlier, snap.lines().get(snap.indexOf(SearchHit.from(earlier))));
        assertThrows(UnsupportedOperationException.class, () -> snap.lines().add(null));

        assertTrue(session.cancel(run));
        long extension = session.beginRun();
        LoadedLogLine stale = line(20, "bkt", "20:1", "stale");
        session.addMatch(run, SearchHit.from(stale), stale);
        assertEquals(2, session.hitCount(), "a stale run must not add a match");
        assertEquals(2, session.snapshot().lines().size());
        assertTrue(session.snapshot().lines().stream().noneMatch(stored -> stored == stale));

        session.addHit(extension, SearchHit.at(at(40)));
        SearchSession.Snapshot afterHitOnly = session.snapshot();
        assertEquals(3, afterHitOnly.size());
        assertNull(afterHitOnly.lines().get(2), "addHit stores a null line");
        assertSame(earlier, afterHitOnly.lines().get(0));
        assertSame(later, afterHitOnly.lines().get(1));
    }

    @Test
    void markTruncatedOnAStaleRunIsIgnored() {
        SearchSession session = new SearchSession("boom", T0);
        long stopped = session.beginRun();
        assertTrue(session.cancel(stopped));
        long versionAfterCancel = session.version();

        session.markTruncated(stopped, SearchSession.Side.EARLIER);
        session.markTruncated(stopped, SearchSession.Side.LATER);

        assertFalse(session.isTruncated(SearchSession.Side.EARLIER));
        assertFalse(session.isTruncated(SearchSession.Side.LATER));
        assertEquals(versionAfterCancel, session.version());
        assertFalse(session.snapshot().truncatedEarlier());
        assertFalse(session.snapshot().truncatedLater());

        long run = session.beginRun();
        long before = session.version();
        AtomicInteger notifications = new AtomicInteger();
        session.addUpdateListener(s -> notifications.incrementAndGet());

        session.markTruncated(run, SearchSession.Side.LATER);

        assertTrue(session.isTruncated(SearchSession.Side.LATER));
        assertFalse(session.isTruncated(SearchSession.Side.EARLIER));
        assertTrue(session.version() > before);
        assertEquals(1, notifications.get());
        assertTrue(session.snapshot().truncatedLater());
        assertFalse(session.snapshot().truncatedEarlier());
        assertSame(session.snapshot(), session.snapshot());

        long after = session.version();
        session.markTruncated(run, SearchSession.Side.LATER);
        assertEquals(after, session.version(), "marking an already-truncated side is a no-op");
        assertEquals(1, notifications.get());
    }

    @Test
    void coverWindowWidensTheUnionAndBumpsVersionOnlyWhenItGrows() {
        SearchSession session = new SearchSession("boom", T0);
        long v0 = session.version();
        session.coverWindow(null, at(1));
        session.coverWindow(at(1), null);
        assertEquals(v0, session.version(), "a null bound is ignored");

        session.coverWindow(at(10), at(20));
        assertTrue(session.version() > v0);
        assertEquals(at(10), session.windowStart());
        assertEquals(at(20), session.windowEnd());
        SearchSession.Snapshot snap = session.snapshot();
        assertEquals(at(10), snap.windowStart());
        assertEquals(at(20), snap.windowEnd());
        assertSame(snap, session.snapshot());

        long v1 = session.version();
        session.coverWindow(at(12), at(18));
        session.coverWindow(at(20), at(10));
        assertEquals(v1, session.version(), "a covered or equal window must not bump version");
        assertSame(snap, session.snapshot());
        assertEquals(at(10), session.windowStart());
        assertEquals(at(20), session.windowEnd());

        session.coverWindow(at(5), at(25));
        assertTrue(session.version() > v1);
        assertEquals(at(5), session.windowStart());
        assertEquals(at(25), session.windowEnd());
        assertNotSame(snap, session.snapshot());
        assertEquals(at(5), session.snapshot().windowStart());
        assertEquals(at(25), session.snapshot().windowEnd());

        long v2 = session.version();
        session.coverWindow(at(0), at(25));
        assertTrue(session.version() > v2, "widening only the start still bumps version");
        assertEquals(at(0), session.windowStart());
        assertEquals(at(25), session.windowEnd());
    }

    private static LoadedLogLine line(int seconds, String bkt, String cd, String message) {
        Instant t = at(seconds);
        return new LoadedLogLine(LoadedLogLine.MsgType.RAW, "seg", 0, cd, bkt, t, message, t, t, message, false,
                Level.INFO);
    }

    @Test
    void chunkPublicationIsAtomicBudgetedAndRejectsStaleCoverageAndDenseFlags() {
        SearchSession session = new SearchSession("boom", T0);
        long run = session.beginRun();
        List<SearchSession.Snapshot> seen = new ArrayList<>();
        session.addUpdateListener(s -> seen.add(s.snapshot()));
        var first = line(1, "bkt", "1:1", "first");
        var second = line(2, "bkt", "2:1", "second");
        assertEquals(0, session.commitChunk(run, List.of(first, second), at(0), at(3), 2));
        assertEquals(1, seen.size());
        assertEquals(2, seen.getFirst().size());
        assertEquals(at(0), seen.getFirst().windowStart());
        assertEquals(at(3), seen.getFirst().windowEnd());
        var snapshot = session.snapshot();
        assertEquals(-2, session.commitChunk(run, List.of(line(4, "bkt", "4:1", "overflow")), at(3), at(5), 2));
        assertSame(snapshot, session.snapshot());

        session.beginRun();
        assertEquals(-1, session.commitChunk(run, List.of(), at(3), at(5), 2));
        session.coverWindow(run, at(3), at(5));
        session.markTooManyMatchesWithinSecond(run);
        assertSame(snapshot, session.snapshot());
        assertFalse(session.snapshot().tooManyMatchesWithinSecond());
    }
}
