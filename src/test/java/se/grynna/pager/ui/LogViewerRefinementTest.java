package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.Searcher;
import se.grynna.pager.splunk.SplunkLogDataProvider;

import javax.swing.*;
import java.awt.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Instant refinement through the viewer, no window shown. The log has six one-row events; the old
 * search "alpha" holds all of them, the refining term "alpha beta" matches events 0, 2 and 4 locally.
 */
class LogViewerRefinementTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int EVENTS = 6;

    private LogViewer viewer;
    private StatusBar statusBar;
    private SearchManager searchManager;
    private SplunkLogDataProvider logProvider;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();
    private final AtomicReference<Scope> scope = new AtomicReference<>(Scope.of("web", Map.of()));

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        logProvider = mock(SplunkLogDataProvider.class);
        when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, T0.plusSeconds(600)));
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T0.plusSeconds(600)));
        when(logProvider.getStatistics())
                .thenReturn(new LogStatistics(LogStatistics.Status.LOADED, EVENTS, T0, T0.plusSeconds(600)));
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> rowLine(inv.getArgument(0)));
        when(logProvider.peekLine(anyInt())).thenAnswer(inv -> Optional.of(rowLine(inv.getArgument(0))));
        when(logProvider.jump(any(SearchHit.class))).thenAnswer(inv -> {
            Instant time = ((SearchHit) inv.getArgument(0)).splunkTime();
            return (int) ((time.getEpochSecond() - T0.getEpochSecond()) / 10);
        });

        searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(logProvider);
        when(searchManager.scope()).thenAnswer(inv -> scope.get());
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(T0.plusSeconds(3600));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(searchManager.search(any(String.class), any(Instant.class))).thenAnswer(inv -> {
            SearchSession started = new SearchSession(inv.getArgument(0), inv.getArgument(1));
            session.set(started);
            Searcher searcher = mock(Searcher.class);
            when(searcher.getSession()).thenReturn(started);
            return searcher;
        });

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            java.lang.reflect.Field field = LogViewer.class.getDeclaredField("searchManager");
            field.setAccessible(true);
            field.set(viewer, searchManager);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
            viewer.cursorManager().updateSize(50, 200);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    // === provisional rows and count ===

    @Test
    void settingRangeRerunsTheQueryAfterTheProviderResetAndKeepsMatchesOpen() throws Exception {
        onEdt(() -> {
            startOldSearch();
            viewer.toggleMatches();
            Instant from = T0.plusSeconds(120);
            Instant to = T0.plusSeconds(300);
            viewer.setRange(from, to);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(logProvider, searchManager);
            order.verify(logProvider).jump(from, from, to);
            order.verify(searchManager).search("alpha", from);
            org.mockito.Mockito.verify(searchManager, org.mockito.Mockito.never()).searchAround(any(), any());
            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(session.get().getSearchOrigin()).isEqualTo(from);
            assertThat(viewer.hasProvisionalMatches()).isFalse();
            assertThat(matches().rows().eventCount()).isZero();
        });
    }

    @Test
    void aRangeSearchDoesNotMoveTheLogAwayFromTheFirstEventWhenMatchesArrive() throws Exception {
        onEdt(() -> {
            startOldSearch();
            viewer.setRange(T0, T0.plusSeconds(300));
            SearchSessionTestSupport.commitChunk(session.get(), List.of(confirmed(2)), T0, T0.plusSeconds(300));
            SearchSessionTestSupport.complete(session.get());
        });
        onEdt(() -> {
            org.mockito.Mockito.verify(logProvider).jump(T0, T0, T0.plusSeconds(300));
            org.mockito.Mockito.verify(logProvider, org.mockito.Mockito.never()).jump(any(SearchHit.class));
            assertThat(viewer.isMatchesView()).isFalse();
        });
    }

    @Test
    void settingRangeLeavesBookmarksForTheLogButAnInvalidRangeLeavesEverythingAlone() throws Exception {
        onEdt(() -> {
            viewer.showBookmarks();
            LogDataProvider bookmarks = viewer.cursorManager().captureViewState().provider();
            viewer.setRange(T0.plusSeconds(300), T0.plusSeconds(120));
            assertThat(viewer.cursorManager().captureViewState().provider()).isSameAs(bookmarks);
            org.mockito.Mockito.verify(logProvider, org.mockito.Mockito.never()).jump(any(), any(), any());

            viewer.setRange(T0.plusSeconds(120), T0.plusSeconds(300));
            assertThat(viewer.cursorManager().captureViewState().provider()).isSameAs(logProvider);
            assertThat(viewer.isMatchesView()).isFalse();
        });
    }

    @Test
    void refiningInTheMatchesViewShowsTheProvisionalRowsAtOnceWithATilde() throws Exception {
        onEdt(() -> {
            openMatchesOnOldSearch();
            assertThat(matches().rows().eventCount()).isEqualTo(EVENTS);

            viewer.executeSearch("alpha beta");

            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(matches().rows().eventCount()).isEqualTo(3);
            assertThat(matches().hitAt(0)).isEqualTo(hit(0));
            assertThat(matches().hitAt(1)).isEqualTo(hit(2));
            assertThat(matches().hitAt(2)).isEqualTo(hit(4));
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES ~3");
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
            assertThat(viewer.hasProvisionalMatches()).isTrue();
            // The session itself knows nothing yet: log navigation stays on the confirmed matches
            assertThat(session.get().hitCount()).isZero();
        });
    }

    @Test
    void refiningInTheLogViewShowsTheTildeCountToo() throws Exception {
        onEdt(() -> {
            startOldSearch();

            viewer.executeSearch("alpha beta");

            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES ~3");
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
        });
    }

    @Test
    void theViewStaysStillWhileConfirmedChunksReplaceTheProvisionalRows() throws Exception {
        onEdt(() -> {
            openMatchesOnOldSearch();
            viewer.executeSearch("alpha beta");
            viewer.cursorManager().ensureCursorAt(2); // event 4, provisional
            assertThat(cursorHit()).isEqualTo(hit(4));
        });

        // Splunk covers event 2's seconds and confirms it
        onEdt(() -> SearchSessionTestSupport.commitChunk(session.get(), List.of(confirmed(2)), T0.plusSeconds(15),
                T0.plusSeconds(25)));
        onEdt(() -> {
            assertThat(matches().rows().eventCount()).isEqualTo(3);
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES ~3");
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
            assertThat(cursorHit()).isEqualTo(hit(4));
        });

        // ... and event 0's: confirmed too, so one provisional row is left
        onEdt(() -> SearchSessionTestSupport.commitChunk(session.get(), List.of(confirmed(0)), T0.minusSeconds(5),
                T0.plusSeconds(15)));
        onEdt(() -> {
            assertThat(matches().rows().eventCount()).isEqualTo(3);
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES ~3");
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
            assertThat(cursorHit()).isEqualTo(hit(4));
            assertThat(viewer.hasProvisionalMatches()).isTrue();
        });

        // The session finishes: Splunk never confirmed event 4 (tokenised), so it goes
        onEdt(() -> SearchSessionTestSupport.complete(session.get()));
        onEdt(() -> {
            assertThat(matches().rows().eventCount()).isEqualTo(2);
            assertThat(matches().hitAt(0)).isEqualTo(hit(0));
            assertThat(matches().hitAt(1)).isEqualTo(hit(2));
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES 2");
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
            assertThat(viewer.hasProvisionalMatches()).isFalse();
        });
    }

    @Test
    void theAnchorFallsBackToTheNearestMatchByTimeWhenItsEventIsNotConfirmed() throws Exception {
        onEdt(() -> {
            openMatchesOnOldSearch();
            viewer.executeSearch("alpha beta");
            viewer.cursorManager().ensureCursorAt(2); // event 4, provisional only
            assertThat(cursorHit()).isEqualTo(hit(4));
        });

        // Splunk covers event 4's seconds and finds nothing there
        onEdt(() -> SearchSessionTestSupport.commitChunk(session.get(), List.of(), T0.plusSeconds(35),
                T0.plusSeconds(45)));
        onEdt(() -> {
            assertThat(matches().rows().eventCount()).isEqualTo(2);
            assertThat(cursorHit()).isEqualTo(hit(2));
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES ~2");
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
        });
    }

    @Test
    void cancellingTheSessionLeavesOnlyWhatWasConfirmed() throws Exception {
        onEdt(() -> {
            openMatchesOnOldSearch();
            viewer.executeSearch("alpha beta");
        });
        onEdt(() -> SearchSessionTestSupport.commitChunk(session.get(), List.of(confirmed(2)), T0.plusSeconds(15),
                T0.plusSeconds(25)));
        onEdt(() -> SearchSessionTestSupport.cancel(session.get()));
        onEdt(() -> {
            assertThat(matches().rows().eventCount()).isEqualTo(1);
            assertThat(matches().hitAt(0)).isEqualTo(hit(2));
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
            assertThat(viewer.hasProvisionalMatches()).isFalse();
        });
    }

    // === no provisional rows ===

    @Test
    void aNonRefiningTermShowsNoProvisionalRows() throws Exception {
        onEdt(() -> {
            openMatchesOnOldSearch();

            viewer.executeSearch("gamma");

            assertThat(matches().rows().eventCount()).isZero();
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
            assertThat(viewer.hasProvisionalMatches()).isFalse();
        });
    }

    @Test
    void aWildcardOrQuotedTermIsNotRefiningEvenWhenItContainsTheOldOne() throws Exception {
        onEdt(() -> {
            openMatchesOnOldSearch();

            viewer.executeSearch("alpha*");

            assertThat(matches().rows().eventCount()).isZero();
            assertThat(viewer.hasProvisionalMatches()).isFalse();

            session.get().discard();
        });
    }

    @Test
    void aRefinementOfAnOldSearchWithoutMatchesHasNothingToShow() throws Exception {
        onEdt(() -> {
            SearchSession old = new SearchSession("alpha", T0);
            SearchSessionTestSupport.complete(old);
            session.set(old);
            statusBar.setSearchActive(true, "alpha");

            viewer.executeSearch("alpha beta");

            assertThat(viewer.hasProvisionalMatches()).isFalse();
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
        });
    }

    @Test
    void chainedRefinementsNarrowTheProvisionalRowsFurther() throws Exception {
        onEdt(() -> {
            openMatchesOnOldSearch();
            viewer.executeSearch("alpha beta");
            assertThat(matches().rows().eventCount()).isEqualTo(3);

            viewer.executeSearch("alpha beta 4");

            assertThat(matches().rows().eventCount()).isEqualTo(1);
            assertThat(matches().hitAt(0)).isEqualTo(hit(4));
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES ~1");
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
        });
    }

    // === clearing ===

    @Test
    void aScopeChangeClearsTheProvisionalMatches() throws Exception {
        scope.set(Scope.of("web", Map.of()).withNarrow("x"));
        onEdt(() -> {
            openMatchesOnOldSearch();
            viewer.executeSearch("alpha beta");
            assertThat(viewer.hasProvisionalMatches()).isTrue();

            viewer.removeScopeTerm("x");

            assertThat(viewer.hasProvisionalMatches()).isFalse();
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
        });
    }

    @Test
    void cancellingTheSearchClearsTheProvisionalMatches() throws Exception {
        onEdt(() -> {
            startOldSearch();
            viewer.executeSearch("alpha beta");
            assertThat(viewer.hasProvisionalMatches()).isTrue();

            viewer.cancelSearch();

            assertThat(viewer.hasProvisionalMatches()).isFalse();
        });
    }

    @Test
    void aNewNonRefiningSearchClearsTheHolderOfThePreviousOne() throws Exception {
        onEdt(() -> {
            startOldSearch();
            viewer.executeSearch("alpha beta");
            assertThat(viewer.hasProvisionalMatches()).isTrue();

            viewer.executeSearch("zeta");

            assertThat(viewer.hasProvisionalMatches()).isFalse();
            assertThat(statusBar.searchPromptText()).doesNotContain("~");
        });
    }

    // === a search that ended without matches (F3 reopens the prompt) ===

    @Test
    void aSearchEndedWithoutMatchesOnlyOnceItIsNoLongerRunning() throws Exception {
        onEdt(() -> {
            SearchSession empty = new SearchSession("zeta", T0);
            session.set(empty);
            statusBar.setSearchActive(true, "zeta");
            assertThat(viewer.searchEndedWithoutMatches()).as("still running").isFalse();

            SearchSessionTestSupport.complete(empty);
            assertThat(viewer.searchEndedWithoutMatches()).isTrue();

            statusBar.setSearchActive(false, "");
            assertThat(viewer.searchEndedWithoutMatches()).as("no active search").isFalse();
        });
    }

    @Test
    void aStoppedSearchWithoutMatchesCountsToo() throws Exception {
        onEdt(() -> {
            SearchSession empty = new SearchSession("zeta", T0);
            SearchSessionTestSupport.cancel(empty);
            session.set(empty);
            statusBar.setSearchActive(true, "zeta");
            assertThat(viewer.searchEndedWithoutMatches()).isTrue();
        });
    }

    @Test
    void aFinishedSearchWithMatchesHasSomethingToStepThrough() throws Exception {
        onEdt(() -> {
            startOldSearch();
            assertThat(viewer.searchEndedWithoutMatches()).isFalse();
        });
    }

    // === helpers ===

    private MatchesDataProvider matches() {
        return (MatchesDataProvider) viewer.cursorManager().captureViewState().provider();
    }

    private SearchHit cursorHit() {
        return matches().hitAt(viewer.cursorManager().cursorLine().orElseThrow());
    }

    private void startOldSearch() {
        SearchSession old = new SearchSession("alpha", T0);
        for (int event = 0; event < EVENTS; event++) {
            SearchSessionTestSupport.addMatch(old, hit(event), eventLine(event));
        }
        SearchSessionTestSupport.complete(old);
        session.set(old);
        statusBar.setSearchActive(true, "alpha");
    }

    private void openMatchesOnOldSearch() throws Exception {
        startOldSearch();
        // The first visible event anchors the Matches view; there is no painted page to derive it from.
        java.lang.reflect.Field anchor = LogViewer.class.getDeclaredField("firstVisibleSearchHit");
        anchor.setAccessible(true);
        anchor.set(viewer, hit(0));
        viewer.toggleMatches();
        assertThat(viewer.isMatchesView()).isTrue();
    }

    private static SearchHit hit(int event) {
        return SearchHit.from(eventLine(event));
    }

    /** Events 0, 2, 4 contain "alpha beta", the others only "alpha". */
    private static LoadedLogLine eventLine(int event) {
        String raw = event % 2 == 0 ? "alpha beta " + event : "alpha " + event;
        Instant time = T0.plusSeconds(10L * event);
        return new LoadedLogLine(MsgType.RAW, "seg", event, "cb" + event, "bkt", time, raw, time, time,
                "message " + event, false, Level.INFO);
    }

    /** The line Splunk would deliver for the same event: same identity. */
    private static LoadedLogLine confirmed(int event) {
        return eventLine(event);
    }

    private static LoadedLogLine rowLine(int row) {
        return eventLine(row);
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        return flatten(root).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<Component> flatten(Container root) {
        List<Component> all = new ArrayList<>();
        for (Component child : root.getComponents()) {
            all.add(child);
            if (child instanceof Container container) {
                all.addAll(flatten(container));
            }
        }
        return all;
    }

    private static void onEdt(ThrowingRunnable r) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            if (failure[0] instanceof Exception e) throw e;
            if (failure[0] instanceof Error e) throw e;
            throw new RuntimeException(failure[0]);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
