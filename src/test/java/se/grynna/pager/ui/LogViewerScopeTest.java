package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import se.grynna.pager.db.ScopeTerms;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Narrow / exclude / remove-term decisions of {@link LogViewer}, with a mocked
 * manager and provider: what scope is set, that it is set before the provider is
 * asked to jump, where the jump lands, and that nothing happens when the guards say no.
 */
class LogViewerScopeTest {
    private static final Instant ORIGIN = Instant.parse("2017-03-30T10:00:00Z");
    private static final Instant LATEST = ORIGIN.plusSeconds(3600);

    private LogViewer viewer;
    private StatusBar statusBar;
    private SearchManager searchManager;
    private SplunkLogDataProvider provider;
    private final AtomicReference<Scope> scope = new AtomicReference<>(Scope.of("web", Map.of("host", "a1")));
    private final AtomicReference<SearchSession> session = new AtomicReference<>();

    private static SearchHit hit(int seconds) {
        Instant time = ORIGIN.plusSeconds(seconds);
        return new SearchHit(time, time, "bkt", "1:" + seconds);
    }

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        provider = mock(SplunkLogDataProvider.class);
        searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(provider);
        when(searchManager.scope()).thenAnswer(inv -> scope.get());
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(LATEST);
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(searchManager.storeFieldFilters(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        doAnswer(inv -> {
            scope.set(inv.getArgument(0));
            session.set(null);
            return null;
        }).when(searchManager).setScope(any());

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            // The login flow owns this field; the test installs the mock the same way it would be set
            java.lang.reflect.Field field = LogViewer.class.getDeclaredField("searchManager");
            field.setAccessible(true);
            field.set(viewer, searchManager);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    private void startSearch(String term, SearchHit... hits) {
        SearchSession active = SearchSessionTestSupport.sessionWith(term, ORIGIN, hits);
        SearchSessionTestSupport.complete(active);
        session.set(active);
        statusBar.setSearchActive(true, term);
    }

    @Test
    void narrowSetsTheScopeThenReloadsAtTheMatchNearestTheOrigin() throws Exception {
        onEdt(() -> {
            startSearch("boom", hit(-100), hit(10), hit(50));
            clearInvocations();

            viewer.narrowScopeToSearch();

            InOrder order = inOrder(searchManager, provider);
            order.verify(searchManager).storeScopeTerms("web", new ScopeTerms(List.of("boom"), List.of()));
            ArgumentCaptor<Scope> captured = ArgumentCaptor.forClass(Scope.class);
            order.verify(searchManager).setScope(captured.capture());
            order.verify(provider).jump(hit(10));
            assertThat(captured.getValue().narrow()).containsExactly("boom");
            assertThat(captured.getValue().fieldFilters()).containsEntry("host", List.of("a1"));

            assertThat(statusBar.isSearchActive()).isFalse();
            // Only search criteria highlight: the narrowed term does not once the search ended
            assertThat(viewer.getActiveSearchTerm()).isEmpty();
            assertThat(viewer.getHighlightTerms()).isEmpty();
        });
    }

    @Test
    void narrowWithoutMatchesChangesNothing() throws Exception {
        onEdt(() -> {
            startSearch("boom");
            clearInvocations();

            viewer.narrowScopeToSearch();

            verify(searchManager, never()).setScope(any());
            verify(searchManager, never()).storeScopeTerms(any(), any());
            verify(provider, never()).jump(any(Instant.class));
            verify(provider, never()).jump(any(SearchHit.class));
            assertThat(statusBar.isSearchActive()).isTrue();
        });
    }

    @Test
    void narrowWithoutASearchChangesNothing() throws Exception {
        onEdt(() -> {
            clearInvocations();
            viewer.narrowScopeToSearch();
            verify(searchManager, never()).setScope(any());
            assertThat(statusBar.noticeText()).isEqualTo("Nothing to narrow: start a search first");
            assertThat(statusBar.noticeColor()).isEqualTo(DarculaColors.ORANGE);
        });
    }

    @Test
    void narrowingWithoutMatchesSaysNothingWouldBeLeftInTheStatusBar() throws Exception {
        onEdt(() -> {
            startSearch("boom");
            clearInvocations();

            viewer.narrowScopeToSearch();

            assertThat(statusBar.noticeText()).isEqualTo("Nothing would be left");
            assertThat(statusBar.isNoticeTimerRunning()).isTrue();
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void rejectedExcludeTermIsNoticedInTheStatusBar() throws Exception {
        onEdt(() -> {
            startSearch("*", hit(10));
            clearInvocations();

            viewer.excludeSearchFromScope();

            assertThat(statusBar.noticeText()).isEqualTo("Cannot exclude \"*\": use a plain term");
            assertThat(statusBar.noticeColor()).isEqualTo(DarculaColors.ORANGE);
        });
    }

    @Test
    void endKeyHintIsAStatusBarNotice() throws Exception {
        onEdt(() -> {
            startSearch("boom", hit(10));

            viewer.clearSearchTransient("Esc");

            assertThat(statusBar.noticeText()).isEqualTo("Esc again to end search");
            assertThat(statusBar.noticeColor()).isEqualTo(DarculaColors.FOREGROUND);
        });
    }

    @Test
    void narrowingTheSameTermTwiceDoesNotReloadAgain() throws Exception {
        scope.set(scope.get().withNarrow("boom"));
        onEdt(() -> {
            startSearch("boom", hit(10));
            clearInvocations();

            viewer.narrowScopeToSearch();

            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void excludeSetsTheScopeAndReloadsAtTheTopRowTime() throws Exception {
        Instant top = ORIGIN.minusSeconds(77);
        onEdt(() -> {
            startSearch("noise", hit(10));
            statusBar.setFirstVisibleTimestamp(top);
            clearInvocations();

            viewer.excludeSearchFromScope();

            InOrder order = inOrder(searchManager, provider);
            order.verify(searchManager).storeScopeTerms("web", new ScopeTerms(List.of(), List.of("noise")));
            ArgumentCaptor<Scope> captured = ArgumentCaptor.forClass(Scope.class);
            order.verify(searchManager).setScope(captured.capture());
            order.verify(provider).jump(top);
            assertThat(captured.getValue().exclude()).containsExactly("noise");
            assertThat(statusBar.isSearchActive()).isFalse();
            assertThat(viewer.getHighlightTerms()).isEmpty();
        });
    }

    @Test
    void excludeRejectsAWildcardOnlyTerm() throws Exception {
        onEdt(() -> {
            startSearch("*", hit(10));
            clearInvocations();

            viewer.excludeSearchFromScope();

            verify(searchManager, never()).setScope(any());
            verify(provider, never()).jump(any(Instant.class));
            assertThat(statusBar.isSearchActive()).isTrue();
        });
    }

    @Test
    void aTargetAfterTheLatestInstantIsClamped() throws Exception {
        onEdt(() -> {
            startSearch("noise", hit(10));
            statusBar.setFirstVisibleTimestamp(LATEST.plusSeconds(500));
            clearInvocations();

            viewer.excludeSearchFromScope();

            verify(provider).jump(LATEST);
        });
    }

    @Test
    void removeTermReloadsAtTheTopRowTimeAndSavesTheRest() throws Exception {
        scope.set(scope.get().withNarrow("a").withExclude("b"));
        Instant top = ORIGIN.plusSeconds(5);
        onEdt(() -> {
            statusBar.setFirstVisibleTimestamp(top);
            clearInvocations();

            viewer.removeScopeTerm("a");

            InOrder order = inOrder(searchManager, provider);
            order.verify(searchManager).storeScopeTerms("web", new ScopeTerms(List.of(), List.of("b")));
            order.verify(searchManager).setScope(scope.get());
            order.verify(provider).jump(top);
            assertThat(scope.get().narrow()).isEmpty();
            assertThat(scope.get().exclude()).containsExactly("b");
            assertThat(viewer.getHighlightTerms()).isEmpty();
        });
    }

    @Test
    void multiValueFieldFilterShowsOneChipPerValueAndRemovingOneKeepsTheRest() throws Exception {
        scope.set(Scope.ofValues("web", Map.of("pod", List.of("a", "b"))));
        Instant top = ORIGIN.plusSeconds(5);
        onEdt(() -> {
            viewer.loginComplete(searchManager);
            ScopeChips chips = find(viewer, ScopeChips.class).getFirst();
            List<String> chipLabels = find(chips, JLabel.class).stream().map(JLabel::getText).toList();
            assertThat(chipLabels).containsExactly("+pod=\"a\"", "+pod=\"b\"");

            statusBar.setFirstVisibleTimestamp(top);
            clearInvocations();
            viewer.removeScopeTerm(se.grynna.pager.splunk.FieldTerm.of("pod", "a").orElseThrow().encode());

            verify(searchManager).storeFieldFilters("web", Map.of("pod", List.of("b")));
            assertThat(scope.get().fieldFilters()).containsExactlyEntriesOf(Map.of("pod", List.of("b")));
        });
    }

    @Test
    void removingAnUnknownTermDoesNothing() throws Exception {
        onEdt(() -> {
            clearInvocations();
            viewer.removeScopeTerm("nope");
            viewer.removeScopeTerm(null);
            verify(searchManager, never()).setScope(any());
        });
    }

    private void clearInvocations() {
        org.mockito.Mockito.clearInvocations(searchManager, provider);
    }

    private static List<Component> flatten(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            found.add(c);
            if (c instanceof Container container) {
                found.addAll(flatten(container));
            }
        }
        return found;
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        return flatten(root).stream().filter(type::isInstance).map(type::cast).toList();
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
