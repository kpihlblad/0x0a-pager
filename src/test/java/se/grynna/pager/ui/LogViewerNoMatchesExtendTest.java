package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.Searcher;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.testutils.MutableClock;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A search that finds nothing in its window asks to widen it at once: F3 has no match to step to, so the
 * usual end-of-matches question can never be reached. Driven through the viewer with a fake search manager.
 */
class LogViewerNoMatchesExtendTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final Instant LATEST = T0.plusSeconds(3600);
    private static final Instant WINDOW_START = T0.minusSeconds(60);
    private static final Instant WINDOW_END = T0.plusSeconds(60);

    private LogViewer viewer;
    private StatusBar statusBar;
    private SearchManager searchManager;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();
    private final MutableClock clock = new MutableClock(T0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider logProvider = mock(SplunkLogDataProvider.class);
        when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, LATEST));
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, LATEST));
        when(logProvider.getStatistics()).thenReturn(new LogStatistics(LogStatistics.Status.LOADED, 1, T0, LATEST));
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> line(inv.getArgument(0)));
        when(logProvider.peekLine(anyInt())).thenAnswer(inv -> Optional.of(line(inv.getArgument(0))));
        when(logProvider.jump(any(SearchHit.class))).thenReturn(0);

        searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(logProvider);
        when(searchManager.scope()).thenReturn(Scope.of("web", Map.of()));
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(LATEST);
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(searchManager.search(any(String.class), any(Instant.class))).thenAnswer(inv -> {
            SearchSession started = new SearchSession(inv.getArgument(0), inv.getArgument(1));
            session.set(started);
            Searcher searcher = mock(Searcher.class);
            when(searcher.getSession()).thenReturn(started);
            return searcher;
        });
        when(searchManager.searchAround(any(String.class), any(Instant.class)))
                .thenAnswer(inv -> searchManager.search(inv.getArgument(0), inv.getArgument(1)));

        onEdt(() -> {
            viewer = new LogViewer(clock);
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

    @Test
    void aZeroMatchSearchOffersToExtendEarlierWithTheNoMatchesWording() throws Exception {
        onEdt(() -> startSearch("zeta"));
        finishEmpty(WINDOW_START, WINDOW_END);
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isTrue();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_EXTEND_NONE);
            assertThat(statusBar.searchPromptText()).isEqualTo("No matches for \"zeta\" · search");
            BoundaryRequest request = statusBar.searchExtendRequest();
            assertThat(request.type()).isEqualTo(BoundaryType.BEFORE);
            assertThat(request.suggestedBoundary()).isBefore(WINDOW_START);
            assertThat(viewer.isYesNoPromptShowing()).isTrue();
        });
    }

    @Test
    void atTheMinBoundaryItOffersLaterInstead() throws Exception {
        onEdt(() -> startSearch("zeta"));
        finishEmpty(SearchBoundaries.MIN_MINIMUM_BOUNDARY, WINDOW_END);
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isTrue();
            BoundaryRequest request = statusBar.searchExtendRequest();
            assertThat(request.type()).isEqualTo(BoundaryType.AFTER);
            assertThat(request.suggestedBoundary()).isAfter(WINDOW_END);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_EXTEND_NONE);
        });
    }

    @Test
    void whenTheWholeRangeIsCoveredThereIsNoPrompt() throws Exception {
        onEdt(() -> startSearch("zeta"));
        finishEmpty(SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATEST);
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isFalse();
            assertThat(viewer.isYesNoPromptShowing()).isFalse();
            assertThat(viewer.searchEndedWithoutMatches()).isTrue();
        });
    }

    @Test
    void yesWidensTheSameSessionAndAFurtherEmptyResultAsksAgainWithABiggerStep() throws Exception {
        onEdt(() -> startSearch("zeta"));
        SearchSession first = session.get();
        finishEmpty(WINDOW_START, WINDOW_END);
        Instant[] suggested = new Instant[1];
        onEdt(() -> {
            suggested[0] = statusBar.searchExtendRequest().suggestedBoundary();
            assertThat(viewer.actionAcceptBoundaryExpansion(true)).isTrue();
            assertThat(statusBar.hasSearchExtendPrompt()).isFalse();
        });
        verify(searchManager, timeout(2000)).extendSearch(eq(first), eq(suggested[0]), eq(WINDOW_START));

        clock.fastForward(Duration.ofSeconds(11)); // asked again, not extended silently, once the answer is old
        SearchSessionTestSupport.commitChunk(first, List.of(), suggested[0], WINDOW_START);
        SearchSessionTestSupport.complete(first);
        onEdt(() -> {});
        onEdt(() -> {
            assertThat(session.get()).isSameAs(first);
            assertThat(statusBar.hasSearchExtendPrompt()).isTrue();
            assertThat(statusBar.searchExtendRequest().suggestedBoundary()).isBefore(suggested[0]);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_EXTEND_NONE);
        });
    }

    @Test
    void noStopsOfferingAndRepeatedCompletionsDoNotAskAgain() throws Exception {
        onEdt(() -> startSearch("zeta"));
        finishEmpty(WINDOW_START, WINDOW_END);
        onEdt(() -> {
            assertThat(viewer.actionAcceptBoundaryExpansion(false)).isTrue();
            assertThat(statusBar.hasSearchExtendPrompt()).isFalse();
        });
        // Further updates of the same finished state (queued EDT updates, a late side completion).
        SearchSessionTestSupport.complete(session.get());
        SearchSessionTestSupport.complete(session.get());
        onEdt(() -> {});
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isFalse();
            assertThat(viewer.searchEndedWithoutMatches()).isTrue();
        });
    }

    @Test
    void whileThePromptShowsF3DoesNotOpenTheSearchInputAndAfterNoItDoes() throws Exception {
        onEdt(() -> startSearch("zeta"));
        finishEmpty(WINDOW_START, WINDOW_END);
        onEdt(() -> {
            assertThat(viewer.searchEndedWithoutMatches()).as("a question is open").isFalse();
            viewer.searchNext();
            assertThat(statusBar.isSearchInputActive()).isFalse();
            assertThat(statusBar.hasSearchExtendPrompt()).isTrue();

            viewer.actionAcceptBoundaryExpansion(false);
            assertThat(viewer.searchEndedWithoutMatches()).isTrue();
            viewer.openSearchPrompt("zeta");
            assertThat(statusBar.isSearchInputActive()).isTrue();
        });
    }

    @Test
    void aZeroMatchSearchInTheMatchesViewOffersToo() throws Exception {
        onEdt(() -> {
            // The first visible event anchors the Matches view; there is no painted page to derive it from.
            java.lang.reflect.Field anchor = LogViewer.class.getDeclaredField("firstVisibleSearchHit");
            anchor.setAccessible(true);
            anchor.set(viewer, SearchHit.from(line(0)));
            viewer.executeSearch("zeta", true);
            clock.fastForward(Duration.ofSeconds(11));
            assertThat(viewer.isMatchesView()).isTrue();
        });
        finishEmpty(WINDOW_START, WINDOW_END);
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isTrue();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_EXTEND_NONE);
            assertThat(statusBar.searchExtendRequest().type()).isEqualTo(BoundaryType.BEFORE);
        });
    }

    @Test
    void aSearchWithMatchesDoesNotAskOnCompletion() throws Exception {
        onEdt(() -> startSearch("alpha"));
        SearchSession s = session.get();
        SearchSessionTestSupport.addMatch(s, SearchHit.from(line(0)), line(0));
        finishEmpty(WINDOW_START, WINDOW_END);
        onEdt(() -> assertThat(statusBar.hasSearchExtendPrompt()).isFalse());
    }

    @Test
    void aYoungSearchExtendsItselfWithoutAskingAndKeepsGoingUntilItIsOldEnoughToAsk() throws Exception {
        onEdt(() -> startYoungSearch("zeta"));
        SearchSession first = session.get();
        finishEmpty(WINDOW_START, WINDOW_END);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(searchManager, timeout(2000)).extendSearch(eq(first), from.capture(), to.capture());
        onEdt(() -> assertThat(statusBar.hasSearchExtendPrompt()).as("no question while young").isFalse());
        assertThat(to.getValue()).isEqualTo(WINDOW_START);
        Instant firstStep = from.getValue();
        assertThat(firstStep).isBefore(WINDOW_START);

        // The extension also finds nothing, still young: the next, bigger step follows by itself.
        SearchSessionTestSupport.commitChunk(first, List.of(), firstStep, WINDOW_START);
        SearchSessionTestSupport.complete(first);
        onEdt(() -> {});
        ArgumentCaptor<Instant> secondFrom = ArgumentCaptor.forClass(Instant.class);
        verify(searchManager, timeout(2000).times(2)).extendSearch(eq(first), secondFrom.capture(), any());
        Instant secondStep = secondFrom.getValue();
        assertThat(secondStep).isBefore(firstStep);

        // Ten seconds on, the same situation asks.
        clock.fastForward(Duration.ofSeconds(11));
        SearchSessionTestSupport.commitChunk(first, List.of(), secondStep, firstStep);
        SearchSessionTestSupport.complete(first);
        onEdt(() -> {});
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isTrue();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_EXTEND_NONE);
            assertThat(statusBar.searchExtendRequest().suggestedBoundary()).isBefore(secondStep);
        });
    }

    @Test
    void answeringAQuestionRestartsTheYoungPeriod() throws Exception {
        onEdt(() -> startSearch("zeta"));
        SearchSession first = session.get();
        finishEmpty(WINDOW_START, WINDOW_END);
        Instant[] step = new Instant[1];
        onEdt(() -> {
            step[0] = statusBar.searchExtendRequest().suggestedBoundary();
            viewer.actionAcceptBoundaryExpansion(true);
        });
        verify(searchManager, timeout(2000)).extendSearch(eq(first), eq(step[0]), eq(WINDOW_START));

        SearchSessionTestSupport.commitChunk(first, List.of(), step[0], WINDOW_START);
        SearchSessionTestSupport.complete(first);
        onEdt(() -> {});
        // Still empty a moment after the answer: extended without asking.
        verify(searchManager, timeout(2000)).extendSearch(eq(first), any(), eq(step[0]));
        onEdt(() -> assertThat(statusBar.hasSearchExtendPrompt()).isFalse());
    }

    @Test
    void aWindowThatEndedAtNowIsNotOfferedLaterJustBecauseTheClockMovedOn() throws Exception {
        onEdt(() -> startSearch("zeta"));
        // The search covered everything up to the limit a minute ago; the limit has moved since.
        finishEmpty(SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATEST.minusSeconds(60));
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isFalse();
            assertThat(viewer.searchEndedWithoutMatches()).isTrue();
        });
    }

    @Test
    void anOfferThatWasInTheWayIsAskedWhenF3IsPressed() throws Exception {
        onEdt(() -> {
            startSearch("zeta");
            viewer.setSearchInputActive(true); // typing a new term when the old search finishes
        });
        finishEmpty(WINDOW_START, WINDOW_END);
        onEdt(() -> {
            assertThat(statusBar.hasSearchExtendPrompt()).isFalse();
            statusBar.setSearchInputActive(false);
            assertThat(viewer.searchEndedWithoutMatches()).as("the offer is still to be made").isFalse();

            viewer.searchPrevious();

            assertThat(statusBar.hasSearchExtendPrompt()).isTrue();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_EXTEND_NONE);
        });
    }

    @Test
    void zeroMatchCompletionPostsNoNoticeAndTheCentreSaysWhatWasSearched() throws Exception {
        onEdt(() -> startSearch("zeta"));
        finishEmpty(SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATEST);
        onEdt(() -> {
            assertThat(statusBar.noticeText()).as("no toast: the centre says it").isEmpty();
            assertThat(statusBar.isNoticeTimerRunning()).isFalse();
            assertThat(statusBar.searchCenterText()).isEqualTo(
                    "No matches \u00b7 1971 \u2192 " + DateTimeFormatterManagerTestUtil.localTime(LATEST));
            assertThat(statusBar.searchPromptText()).contains("\"zeta\" \u2014 no matches");
        });
    }

    @Test
    void theCentreShowsTheLogsBrowsingRangeWithoutASearch() throws Exception {
        clock.fastForward(Duration.ofHours(2));
        onEdt(() -> {
            viewer.updateVisibleLines();
            assertThat(statusBar.searchCenterText()).isEqualTo(DateTimeFormatterManagerTestUtil.localTime(T0)
                    + " \u2192 " + DateTimeFormatterManagerTestUtil.localTime(LATEST));
            assertThat(statusBar.centerTooltip()).startsWith(DateTimeFormatterManagerTestUtil.localDateMinute(T0));
        });
    }

    @Test
    void anExtensionShowsTheEdgeItMovesToUntilTheSearchStopsRunning() throws Exception {
        doAnswer(inv -> {
            SearchSessionTestSupport.beginRun(inv.getArgument(0));
            return null;
        }).when(searchManager).extendSearch(any(), any(), any());
        clock.fastForward(Duration.ofHours(2));
        onEdt(() -> startSearch("zeta"));
        SearchSession first = session.get();
        finishEmpty(WINDOW_START, WINDOW_END);
        Instant[] suggested = new Instant[1];
        onEdt(() -> {
            suggested[0] = statusBar.searchExtendRequest().suggestedBoundary();
            viewer.actionAcceptBoundaryExpansion(true);
        });
        verify(searchManager, timeout(2000)).extendSearch(eq(first), eq(suggested[0]), eq(WINDOW_START));
        onEdt(() -> {});
        onEdt(() -> {
            assertThat(statusBar.searchCenterText()).isEqualTo("\u2190 "
                    + se.grynna.pager.ui.preferences.DateTimeFormatterManager.formatRangeEndpoint(suggested[0], null,
                            clock.instant()));

        });
        clock.fastForward(Duration.ofSeconds(11)); // old enough to ask again, not to extend by itself
        SearchSessionTestSupport.commitChunk(first, List.of(), suggested[0], WINDOW_START);
        SearchSessionTestSupport.complete(first);
        onEdt(() -> {});
        onEdt(() -> assertThat(statusBar.searchCenterText()).startsWith("No matches \u00b7 "));
    }

    /** A search that has been running for a while: its extensions are asked, not taken silently. */
    private void startSearch(String term) {
        startYoungSearch(term);
        clock.fastForward(Duration.ofSeconds(11));
    }

    private void startYoungSearch(String term) {
        viewer.executeSearch(term);
        assertThat(session.get()).isNotNull();
    }

    /** The searcher covers [start, end) and completes without a hit; then the queued EDT update runs. */
    private void finishEmpty(Instant start, Instant end) throws Exception {
        SearchSession s = session.get();
        SearchSessionTestSupport.coverWindow(s, start, end);
        SearchSessionTestSupport.complete(s);
        onEdt(() -> {});
        onEdt(() -> {});
    }

    private static LoadedLogLine line(int row) {
        Instant time = T0.plusSeconds(10L * row);
        return new LoadedLogLine(MsgType.RAW, "seg", row, "cb" + row, "bkt", time, "alpha " + row, time, time,
                "message " + row, false, Level.INFO);
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
