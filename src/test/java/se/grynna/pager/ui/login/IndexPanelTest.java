package se.grynna.pager.ui.login;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import raven.toast.Notifications;
import se.grynna.pager.db.ScopeTerms;
import se.grynna.pager.db.SearchFilterManager.FieldSummaryUpdateListener;
import se.grynna.pager.db.SearchFilterManager.IndexUpdateListener;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplQuery;

import javax.swing.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cluster B (UI side): filter state and field-summary replies stay with the index
 * they belong to, and refresh failures end progress visibly.
 */
class IndexPanelTest {

    private SearchManager searchManager;
    private IndexPanel panel;
    private FieldSummaryUpdateListener fieldListener;
    private IndexUpdateListener indexListener;

    /** Persisted (or pending) filters per index, as SearchFilterManager exposes them. */
    private final Map<String, Map<String, String>> store = new ConcurrentHashMap<>();

    /** Persisted narrow / exclude terms per index. */
    private final Map<String, ScopeTerms> termStore = new ConcurrentHashMap<>();

    private final List<String> saves = new CopyOnWriteArrayList<>();
    private final List<String> notifications = new CopyOnWriteArrayList<>();

    private final Map<String, SortedSet<FieldSummary>> cachedFields = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        searchManager = mock(SearchManager.class);
        when(searchManager.loadIndexes()).thenReturn(new TreeSet<>(List.of("A", "B")));
        when(searchManager.getLastIndex()).thenReturn("");
        when(searchManager.getFieldFilters(any()))
                .thenAnswer(inv -> store.getOrDefault(inv.<String>getArgument(0), Map.of()));
        // Persistence never completes: the snapshot must be taken when the save is requested
        when(searchManager.storeFieldFilters(any(), any())).thenAnswer(inv -> {
            String index = inv.getArgument(0);
            Map<String, String> filters = inv.getArgument(1);
            store.put(index, Map.copyOf(filters));
            saves.add(index + "=" + new TreeMap<>(filters));
            return new CompletableFuture<Void>();
        });
        when(searchManager.getScopeTerms(any()))
                .thenAnswer(inv -> termStore.getOrDefault(inv.<String>getArgument(0), ScopeTerms.EMPTY));
        when(searchManager.storeScopeTerms(any(), any())).thenAnswer(inv -> {
            termStore.put(inv.<String>getArgument(0), inv.<ScopeTerms>getArgument(1));
            return new CompletableFuture<Void>();
        });
        cachedFields.put("A", fields("app", "pod"));
        cachedFields.put("B", fields("app", "host"));
        when(searchManager.loadFieldSummaries(any(), anyBoolean()))
                .thenAnswer(inv -> cachedFields.getOrDefault(inv.<String>getArgument(0), new TreeSet<>()));

        onEdt(() -> {
            panel = new IndexPanel(searchManager, null, Clock.systemUTC());
            panel.setNotifier((message, type) -> notifications.add(type + ": " + message));
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> onEdt(() -> panel.indexChoiceCount()) == 2);

        var fieldCaptor = ArgumentCaptor.forClass(FieldSummaryUpdateListener.class);
        verify(searchManager).setFieldSummaryUpdateListener(fieldCaptor.capture());
        fieldListener = fieldCaptor.getValue();
        var indexCaptor = ArgumentCaptor.forClass(IndexUpdateListener.class);
        verify(searchManager).setIndexUpdateListener(indexCaptor.capture());
        indexListener = indexCaptor.getValue();
    }

    private static SortedSet<FieldSummary> fields(String... names) {
        // Mocks have no real field, so order them by the stubbed getter
        SortedSet<FieldSummary> set = new TreeSet<>(Comparator.comparing(FieldSummary::getField));
        for (String name : names) {
            FieldSummary summary = mock(FieldSummary.class);
            when(summary.getField()).thenReturn(name);
            when(summary.isRelevantForFiltering()).thenReturn(true);
            Map<String, FieldSummary.Value> values = new TreeMap<>();
            for (String v : List.of("1", "2", "3")) {
                values.put(name + v, new FieldSummary.Value(name + v, 1));
            }
            when(summary.getValues()).thenReturn(values);
            set.add(summary);
        }
        return set;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        var result = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    /** Run everything queued on the EDT, including work those events queue in turn. */
    private static void drainEdt() throws Exception {
        for (int i = 0; i < 3; i++) {
            onEdt(() -> {});
        }
    }

    private void select(String index) throws Exception {
        onEdt(() -> panel.selectIndex(index));
        drainEdt();
    }

    private Map<String, String> rowValues() throws Exception {
        return onEdt(() -> panel.filterRowValues());
    }

    // --- Filters are kept per index ---

    @Test
    void eachIndexKeepsItsOwnFiltersAcrossRapidSwitches() throws Exception {
        select("A");
        onEdt(() -> panel.setFilterRowValue("app", "app1"));

        select("B");
        assertThat(rowValues()).isEmpty();
        onEdt(() -> panel.setFilterRowValue("app", "app2"));
        onEdt(() -> panel.setFilterRowValue("host", "host3"));

        select("A");
        assertThat(rowValues()).containsExactlyEntriesOf(Map.of("app", "app1"));
        select("B");
        assertThat(rowValues()).isEqualTo(Map.of("app", "app2", "host", "host3"));

        assertThat(saves).containsExactly("A={app=app1}", "B={app=app2, host=host3}", "A={app=app1}");
        assertThat(store.get("A")).isEqualTo(Map.of("app", "app1"));
        assertThat(store.get("B")).isEqualTo(Map.of("app", "app2", "host", "host3"));
    }

    // --- Replies for another index are ignored ---

    @Test
    void queuedReplyForThePreviousIndexDoesNotTouchTheNewOne() throws Exception {
        select("A");
        // A reply for A is queued, then the user switches to B before it runs
        onEdt(() -> {
            fieldListener.onFieldSummariesUpdated("A", fields("stale"), false);
            panel.selectIndex("B");
            panel.fieldRefreshButton().setRefreshing(true); // B's refresh is running
        });
        drainEdt();

        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("app", "host");
        assertThat(onEdt(() -> panel.fieldRefreshButton().isRefreshing())).isTrue();
        String queryForB = onEdt(() -> panel.generatedQuery());
        assertThat(queryForB).isEqualTo("index=\"B\"");

        // A late reply for A while B is shown changes nothing either
        fieldListener.onFieldSummariesUpdated("A", fields("stale"), false);
        drainEdt();
        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("app", "host");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo(queryForB);
        assertThat(onEdt(() -> panel.fieldRefreshButton().isRefreshing())).isTrue();
    }

    // --- Edits survive a refresh ---

    @Test
    void editsSurviveBothTheCachedAndTheFreshSummary() throws Exception {
        store.put("A", Map.of("pod", "pod1"));
        select("A");
        assertThat(rowValues()).isEqualTo(Map.of("pod", "pod1"));

        onEdt(() -> panel.setFilterRowValue("app", "app2"));
        fieldListener.onFieldSummariesUpdated("A", fields("app", "pod", "extra"), true);
        drainEdt();
        assertThat(rowValues()).isEqualTo(Map.of("app", "app2", "pod", "pod1"));

        // Edit again while the request runs, including clearing a restored value
        onEdt(() -> {
            panel.setFilterRowValue("app", "app3");
            panel.setFilterRowValue("pod", "");
        });
        fieldListener.onFieldSummariesUpdated("A", fields("app", "pod", "extra"), false);
        drainEdt();

        assertThat(rowValues()).isEqualTo(Map.of("app", "app3"));
        // pod had a saved value on entry, so it stays pinned first for the visit
        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("pod", "app", "extra");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"A\" app=\"app3\"");
    }

    @Test
    void persistedFiltersAreRestoredWhenFieldsArriveAfterAnEmptyCache() throws Exception {
        store.put("A", Map.of("app", "app1"));
        cachedFields.put("A", new TreeSet<>());
        select("A");
        assertThat(onEdt(() -> panel.filterRowFields())).isEmpty();

        fieldListener.onFieldSummariesUpdated("A", fields("app", "pod"), false);
        drainEdt();

        assertThat(rowValues()).isEqualTo(Map.of("app", "app1"));
    }

    // --- Grouped, merged and found field rows ---

    @Test
    void twinFieldsShareOneRowAndTheAppliedScopeMatchesEither() throws Exception {
        // Saved before the twins were merged, under the flat name
        store.put("A", Map.of("k8s_pod_name", "p1"));
        cachedFields.put("A", fields("app", "k8s.pod_name", "k8s_pod_name"));
        select("A");

        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("k8s.pod_name", "app");
        assertThat(rowValues()).isEqualTo(Map.of("k8s.pod_name", "p1"));

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        Scope scope = captor.getValue();
        assertThat(scope.fieldFilters()).containsExactlyEntriesOf(Map.of("k8s.pod_name", "p1"));
        assertThat(scope.filterFields("k8s.pod_name")).containsExactly("k8s.pod_name", "k8s_pod_name");
        assertThat(SplQuery.scope(scope))
                .isEqualTo("search index=\"A\" (k8s.pod_name=\"p1\" OR k8s_pod_name=\"p1\")");
        // Saved under the primary name only
        assertThat(store.get("A")).isEqualTo(Map.of("k8s.pod_name", "p1"));
    }

    @Test
    void fieldsSharingANamePartAreListedUnderAHeading() throws Exception {
        cachedFields.put("A", fields("app", "kube.a", "kube.b", "kube.c"));
        select("A");

        assertThat(onEdt(() -> panel.visibleHeadings())).containsExactly("0:kube");
        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("app", "kube.a", "kube.b", "kube.c");
    }

    @Test
    void findShowsOnlyMatchingRowsAndHidesHeadingsLeftEmpty() throws Exception {
        cachedFields.put("A", fields("app", "kube.a", "kube.b", "kube.pod"));
        select("A");

        onEdt(() -> panel.setFindText("POD"));
        assertThat(onEdt(() -> panel.visibleFilterRowFields())).containsExactly("kube.pod");
        assertThat(onEdt(() -> panel.visibleHeadings())).containsExactly("0:kube");

        onEdt(() -> panel.setFindText("app"));
        assertThat(onEdt(() -> panel.visibleFilterRowFields())).containsExactly("app");
        assertThat(onEdt(() -> panel.visibleHeadings())).isEmpty();

        onEdt(() -> panel.setFindText(""));
        assertThat(onEdt(() -> panel.visibleFilterRowFields())).hasSize(4);
    }

    @Test
    void fieldsWithASavedValueArePinnedFirstUnderSet() throws Exception {
        store.put("A", Map.of("pod", "pod1"));
        select("A");

        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("pod", "app");
        assertThat(onEdt(() -> panel.visibleHeadings())).containsExactly("0:set", "0:fields");
    }

    // --- Scope (narrow / exclude terms) ---

    @Test
    void applyBuildsAScopeFromTheFieldFiltersAndTheSavedTerms() throws Exception {
        termStore.put("A", new ScopeTerms(List.of("timeout"), List.of("healthcheck")));
        select("A");
        onEdt(() -> panel.setFilterRowValue("app", "app1"));

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        Scope scope = captor.getValue();
        assertThat(scope.index()).isEqualTo("A");
        assertThat(scope.fieldFilters()).containsExactlyEntriesOf(Map.of("app", "app1"));
        assertThat(scope.narrow()).containsExactly("timeout");
        assertThat(scope.exclude()).containsExactly("healthcheck");
        assertThat(scope.manualSpl()).isNull();
        assertThat(SplQuery.scope(scope)).isEqualTo(
                "search index=\"A\" app=\"app1\" | search \"timeout\" | search NOT \"healthcheck\"");
    }

    @Test
    void withoutTermsTheAppliedScopeRendersTheGeneratedQuery() throws Exception {
        select("A");
        onEdt(() -> panel.setFilterRowValue("pod", "pod2"));
        onEdt(() -> panel.setFilterRowValue("app", "app1"));
        String generated = onEdt(() -> panel.generatedQuery());

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        assertThat(SplQuery.scope(captor.getValue())).isEqualTo(SplQuery.baseline(generated));
    }

    // --- Bookmarks outside the new scope ---

    /** Records the question and answers it; the guard reports {@code outside} bookmarks and records drops. */
    private final class FakeBookmarks implements BookmarkScopeGuard, Confirmer {
        final int outside;
        final boolean answer;
        final List<String> events = new CopyOnWriteArrayList<>();

        FakeBookmarks(int outside, boolean answer) {
            this.outside = outside;
            this.answer = answer;
        }

        @Override
        public int outside(Scope newScope) {
            events.add("outside " + newScope.index() + " " + newScope.fieldFilters());
            return outside;
        }

        @Override
        public void drop(Scope newScope) {
            events.add("drop");
        }

        @Override
        public boolean confirm(java.awt.Component parent, String message) {
            events.add("confirm: " + message);
            return answer;
        }
    }

    private FakeBookmarks installBookmarks(int outside, boolean answer) throws Exception {
        FakeBookmarks fake = new FakeBookmarks(outside, answer);
        onEdt(() -> {
            panel.setBookmarkGuard(fake);
            panel.setConfirmer(fake);
        });
        return fake;
    }

    @Test
    void applyWithBookmarksOutsideAsksAndOnYesDropsThemBeforeSettingTheScope() throws Exception {
        FakeBookmarks fake = installBookmarks(3, true);
        select("A");
        onEdt(() -> panel.setFilterRowValue("app", "app1"));
        AtomicInteger completed = new AtomicInteger();
        onEdt(() -> panel.setOnLoginComplete(sm -> completed.incrementAndGet()));

        onEdt(() -> panel.apply());

        assertThat(fake.events).hasSize(3);
        assertThat(fake.events.get(0)).isEqualTo("outside A {app=app1}");
        assertThat(fake.events.get(1)).startsWith("confirm: 3 bookmarks are outside the new scope and will be removed")
                .contains("Copy bookmarks").contains("Export");
        assertThat(fake.events.get(2)).isEqualTo("drop");
        var order = inOrder(searchManager);
        order.verify(searchManager).setScope(any(Scope.class));
        assertThat(completed).hasValue(1);
    }

    @Test
    void applyWithBookmarksOutsideAndNoStaysInThePanelWithoutSavingOrSettingTheScope() throws Exception {
        FakeBookmarks fake = installBookmarks(2, false);
        select("A");
        onEdt(() -> panel.setFilterRowValue("app", "app1"));
        saves.clear();
        AtomicInteger completed = new AtomicInteger();
        onEdt(() -> panel.setOnLoginComplete(sm -> completed.incrementAndGet()));

        onEdt(() -> panel.apply());

        assertThat(fake.events).noneMatch("drop"::equals);
        assertThat(fake.events).anyMatch(e -> e.startsWith("confirm: 2 bookmarks"));
        verify(searchManager, never()).setScope(any());
        assertThat(saves).isEmpty();
        assertThat(completed).hasValue(0);
    }

    @Test
    void applyWithNoBookmarkOutsideNeverAsks() throws Exception {
        FakeBookmarks fake = installBookmarks(0, false);
        select("A");

        onEdt(() -> panel.apply());

        assertThat(fake.events).hasSize(1).first().asString().startsWith("outside A");
        verify(searchManager).setScope(any(Scope.class));
    }

    @Test
    void switchingIndexIsCheckedOnApplyLikeAnyScopeChange() throws Exception {
        FakeBookmarks fake = installBookmarks(1, true);
        select("A");
        select("B");

        onEdt(() -> panel.apply());

        assertThat(fake.events.getFirst()).startsWith("outside B");
        assertThat(fake.events.get(1)).startsWith("confirm: 1 bookmark is outside the new scope");
        assertThat(fake.events.get(2)).isEqualTo("drop");
        verify(searchManager).setScope(any(Scope.class));
    }

    @Test
    void advancedModeKeepsTheSavedTermsAsStagesAfterTheManualQuery() throws Exception {
        termStore.put("A", new ScopeTerms(List.of("timeout"), List.of()));
        select("A");
        onEdt(() -> panel.switchToAdvancedWith("index=A | head 100"));

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        assertThat(captor.getValue().manualSpl()).isEqualTo("index=A | head 100");
        assertThat(SplQuery.scope(captor.getValue())).isEqualTo("search index=A | head 100 | search \"timeout\"");
    }

    @Test
    void switchingIndexDoesNotCarryTermsOver() throws Exception {
        termStore.put("A", new ScopeTerms(List.of("timeout"), List.of()));
        select("A");
        select("B");

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo("B");
        assertThat(captor.getValue().hasTerms()).isFalse();
    }

    @Test
    void savedTermsAreListedAndARemovedTermIsLeftOutOfApply() throws Exception {
        termStore.put("A", new ScopeTerms(List.of("timeout"), List.of("healthcheck")));
        select("A");
        assertThat(onEdt(() -> panel.scopeTermRows())).hasSize(2);

        // The first row is the narrowing term; its × removes it from the scope
        onEdt(() -> panel.scopeTermRows().get(0).removeButton().doClick());
        assertThat(onEdt(() -> panel.scopeTermRows())).hasSize(1);

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        assertThat(captor.getValue().narrow()).isEmpty();
        assertThat(captor.getValue().exclude()).containsExactly("healthcheck");
        assertThat(termStore.get("A")).isEqualTo(new ScopeTerms(List.of(), List.of("healthcheck")));
    }

    @Test
    void editedTermsSurviveARefreshOfTheFieldRows() throws Exception {
        termStore.put("A", new ScopeTerms(List.of("timeout"), List.of("healthcheck")));
        select("A");
        onEdt(() -> panel.scopeTermRows().get(1).removeButton().doClick());

        onEdt(() -> fieldListener.onFieldSummariesUpdated("A", fields("app", "pod"), false));
        drainEdt();

        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("app", "pod");
        assertThat(onEdt(() -> panel.scopeTermRows())).hasSize(1);

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        assertThat(captor.getValue().narrow()).containsExactly("timeout");
        assertThat(captor.getValue().exclude()).isEmpty();
    }

    // --- Refresh failures ---

    @Test
    void fieldRefreshFailureStopsProgressAndReportsTheError() throws Exception {
        select("A");
        onEdt(() -> panel.fieldRefreshButton().setRefreshing(true));

        fieldListener.onFieldSummaryRefreshFailed("B", new SearchException("other index", new RuntimeException()));
        drainEdt();
        assertThat(onEdt(() -> panel.fieldRefreshButton().isRefreshing())).isTrue();
        assertThat(notifications).isEmpty();

        fieldListener.onFieldSummaryRefreshFailed("A",
                new SearchException("permission denied", new RuntimeException()));
        drainEdt();

        assertThat(onEdt(() -> panel.fieldRefreshButton().isRefreshing())).isFalse();
        assertThat(onEdt(() -> panel.fieldRefreshButton().isEnabled())).isTrue();
        assertThat(notifications).singleElement().asString()
                .startsWith(Notifications.Type.ERROR + ": Failed to load field summaries");
    }

    @Test
    void indexRefreshFailureStopsProgressAndReportsTheError() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        doAnswer(inv -> refreshes.incrementAndGet()).when(searchManager).refreshIndexes();
        onEdt(() -> panel.indexRefreshButton().setRefreshing(true));

        indexListener.onIndexRefreshFailed(new SearchException("permission denied", new RuntimeException()));
        drainEdt();

        assertThat(onEdt(() -> panel.indexRefreshButton().isRefreshing())).isFalse();
        assertThat(notifications).singleElement().asString()
                .startsWith(Notifications.Type.ERROR + ": Failed to refresh indexes");

        onEdt(() -> panel.indexRefreshButton().doClick());
        assertThat(refreshes).hasValue(1);
    }

    @Test
    void fieldRefreshButtonSamplesDeeperInsteadOfReloadingTheSame() throws Exception {
        when(searchManager.deepenFieldSummaries(any()))
                .thenAnswer(inv -> cachedFields.getOrDefault(inv.<String>getArgument(0), new TreeSet<>()));
        select("A");

        onEdt(() -> panel.fieldRefreshButton().doClick());
        drainEdt();

        verify(searchManager).deepenFieldSummaries("A");
        verify(searchManager, never()).loadFieldSummaries(any(), eq(true));
        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("app", "pod");
    }

    // --- Several indexes or all ---

    @Test
    void selectingSeveralIndexesLoadsTheFieldSummariesUnderTheirKey() throws Exception {
        cachedFields.put("A,B", fields("app", "host"));

        select("A,B");

        verify(searchManager).loadFieldSummaries("A,B", false);
        assertThat(onEdt(() -> panel.filterRowFields())).containsExactly("app", "host");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("(index=\"A\" OR index=\"B\")");
    }

    @Test
    void severalIndexesKeepFiltersAndTermsUnderTheirKeyApartFromEachIndex() throws Exception {
        cachedFields.put("A,B", fields("app", "host"));
        select("A");
        onEdt(() -> panel.setFilterRowValue("app", "app1"));

        select("A,B");
        assertThat(rowValues()).isEmpty();
        onEdt(() -> panel.setFilterRowValue("app", "both"));

        select("A");
        assertThat(rowValues()).isEqualTo(Map.of("app", "app1"));
        select("A,B");
        assertThat(rowValues()).isEqualTo(Map.of("app", "both"));

        assertThat(store.get("A")).isEqualTo(Map.of("app", "app1"));
        assertThat(store.get("A,B")).isEqualTo(Map.of("app", "both"));
        assertThat(saves).contains("A={app=app1}", "A,B={app=both}");
        verify(searchManager, atLeastOnce()).storeScopeTerms(eq("A,B"), any());
        verify(searchManager, atLeastOnce()).setLastIndex("A,B");
    }

    @Test
    void applyWithSeveralIndexesBuildsAnOrOfTheIndexes() throws Exception {
        select("B,A");

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo("A,B");
        assertThat(SplQuery.scope(captor.getValue())).startsWith("search (index=\"A\" OR index=\"B\")");
    }

    @Test
    void applyWithAllIndexesSearchesIndexStar() throws Exception {
        select("*");

        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=*");
        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo("*");
        assertThat(SplQuery.scope(captor.getValue())).isEqualTo("search index=*");
    }

    @Test
    void clearingTheSelectionEmptiesTheQuery() throws Exception {
        select("A");
        select("");

        assertThat(onEdt(() -> panel.generatedQuery())).isEmpty();
        assertThat(onEdt(() -> panel.filterRowFields())).isEmpty();
    }
}
