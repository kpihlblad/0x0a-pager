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
import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplQuery;

import javax.swing.*;
import java.awt.event.KeyEvent;
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

class IndexPanelTest {

    private SearchManager searchManager;
    private IndexPanel panel;
    private FieldSummaryUpdateListener fieldListener;
    private IndexUpdateListener indexListener;

    private final Map<String, Map<String, List<String>>> store = new ConcurrentHashMap<>();
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
        when(searchManager.storeFieldFilters(any(), any())).thenAnswer(inv -> {
            String index = inv.getArgument(0);
            Map<String, List<String>> filters = inv.getArgument(1);
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
        SortedSet<FieldSummary> set = new TreeSet<>(Comparator.comparing(FieldSummary::getField));
        for (String name : names) {
            Map<String, FieldSummary.Value> values = new TreeMap<>();
            values.put(name + "1", new FieldSummary.Value(name + "1", 30));
            values.put(name + "2", new FieldSummary.Value(name + "2", 20));
            values.put(name + "3", new FieldSummary.Value(name + "3", 10));
            set.add(FieldSummary.of(name, values, 0.8));
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

    private static void drainEdt() throws Exception {
        for (int i = 0; i < 3; i++) {
            onEdt(() -> {});
        }
    }

    private void select(String index) throws Exception {
        onEdt(() -> panel.selectIndex(index));
        drainEdt();
    }

    // --- Token lines, completion and suggested fields ---

    @Test
    void acceptingValueInNarrowLineAddsFieldFilterAndShowsInQuery() throws Exception {
        select("A");

        onEdt(() -> {
            panel.narrowLine().setInputText("pod=pod2");
            panel.narrowLine().acceptCompletion();
        });

        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("pod", List.of("pod2")));
        assertThat(onEdt(() -> panel.narrowLine().chipTexts())).containsExactly("pod=pod2 \u00D7");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"A\" pod=\"pod2\"");
    }

    @Test
    void minusAndBangInNarrowLineLandInExclude() throws Exception {
        cachedFields.put("A", fields("app", "level"));
        select("A");

        onEdt(() -> {
            panel.narrowLine().setInputText("-level=level1");
            panel.narrowLine().acceptCompletion();
            panel.narrowLine().setInputText("!healthz");
            panel.narrowLine().acceptCompletion();
        });

        String encodedLevel = FieldTerm.of("level", "level1").orElseThrow().encode();
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEmpty();
        assertThat(onEdt(() -> panel.draft().exclude())).containsExactly(encodedLevel, "healthz");
        assertThat(onEdt(() -> panel.excludeLine().chipTexts()))
                .containsExactly("level=level1 \u00D7", "\"healthz\" \u00D7");
        assertThat(onEdt(() -> panel.generatedQuery()))
                .isEqualTo("index=\"A\" | search NOT level=\"level1\" | search NOT \"healthz\"");
    }

    @Test
    void twoValuesOfOneFieldRenderWithOrBetweenChipsAndGenerateOrPredicate() throws Exception {
        select("A");

        onEdt(() -> {
            panel.narrowLine().setInputText("pod=pod1");
            panel.narrowLine().acceptCompletion();
            panel.narrowLine().setInputText("pod=pod2");
            panel.narrowLine().acceptCompletion();
        });

        assertThat(onEdt(() -> panel.narrowLine().renderedTokens()))
                .containsExactly("pod=pod1 \u00D7", "or", "pod=pod2 \u00D7");
        assertThat(onEdt(() -> panel.generatedQuery()))
                .isEqualTo("index=\"A\" (pod=\"pod1\" OR pod=\"pod2\")");
    }

    @Test
    void backspaceOnEmptyInputSelectsThenRemovesLastChip() throws Exception {
        select("A");
        onEdt(() -> {
            panel.narrowFieldValue("app", "app1");
            panel.narrowFieldValue("pod", "pod2");
        });
        assertThat(onEdt(() -> panel.narrowLine().chipTexts()))
                .containsExactly("app=app1 \u00D7", "pod=pod2 \u00D7");

        onEdt(() -> panel.narrowLine().backspace());
        assertThat(onEdt(() -> panel.narrowLine().selectedChipIndex())).isEqualTo(1);

        onEdt(() -> panel.narrowLine().backspace());
        assertThat(onEdt(() -> panel.narrowLine().selectedChipIndex())).isEqualTo(-1);
        assertThat(onEdt(() -> panel.narrowLine().chipTexts())).containsExactly("app=app1 \u00D7");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"A\" app=\"app1\"");
    }

    @Test
    void enterOnSuggestedValueNarrowsAndMinusExcludes() throws Exception {
        select("A");

        onEdt(() -> {
            SuggestedFieldsPanel grid = panel.suggestedFieldsPanel();
            grid.selectCell(0, 1);
            grid.pressKey(KeyEvent.VK_ENTER, 0);
        });
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("app", List.of("app1")));
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"A\" app=\"app1\"");

        onEdt(() -> {
            SuggestedFieldsPanel grid = panel.suggestedFieldsPanel();
            grid.selectCell(1, 2);
            grid.typeChar('-');
        });
        String encodedPod = FieldTerm.of("pod", "pod2").orElseThrow().encode();
        assertThat(onEdt(() -> panel.draft().exclude())).containsExactly(encodedPod);
        assertThat(onEdt(() -> panel.generatedQuery()))
                .isEqualTo("index=\"A\" app=\"app1\" | search NOT pod=\"pod2\"");
    }

    @Test
    void applySavesMultiValueFiltersAndTerms() throws Exception {
        select("A");
        onEdt(() -> {
            panel.narrowFieldValue("pod", "pod1");
            panel.narrowFieldValue("pod", "pod2");
            panel.narrowLine().setInputText("OrderService");
            panel.narrowLine().acceptCompletion();
            panel.excludeLine().setInputText("level=DEBUG");
            panel.excludeLine().acceptCompletion();
        });

        onEdt(() -> panel.apply());

        String encodedExclude = FieldTerm.of("level", "DEBUG").orElseThrow().encode();
        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        Scope applied = captor.getValue();
        assertThat(applied.fieldFilters()).isEqualTo(Map.of("pod", List.of("pod1", "pod2")));
        assertThat(applied.narrow()).containsExactly("OrderService");
        assertThat(applied.exclude()).containsExactly(encodedExclude);
        assertThat(store.get("A")).isEqualTo(Map.of("pod", List.of("pod1", "pod2")));
        assertThat(termStore.get("A")).isEqualTo(new ScopeTerms(List.of("OrderService"), List.of(encodedExclude)));
    }

    @Test
    void savedLegacyNarrowFieldTermFoldsIntoFieldFilterAndShowsInNarrowLine() throws Exception {
        String narrowPod = FieldTerm.of("pod", "pod2").orElseThrow().encode();
        termStore.put("A", new ScopeTerms(List.of("timeout", narrowPod), List.of()));
        select("A");

        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("pod", List.of("pod2")));
        assertThat(onEdt(() -> panel.draft().narrow())).containsExactly("timeout");
        assertThat(onEdt(() -> panel.narrowLine().chipTexts()))
                .containsExactly("pod=pod2 \u00D7", "\"timeout\" \u00D7");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"A\" pod=\"pod2\" | search \"timeout\"");
    }

    @Test
    void allFieldsPopupListsGroupedFieldsAndFiltersByNameOrValue() throws Exception {
        cachedFields.put("A", fields("app", "kube.a", "kube.b", "kube.pod"));
        select("A");

        AllFieldsPopup popup = onEdt(() -> panel.openAllFieldsPopup(""));
        assertThat(onEdt(popup::visibleHeadings)).containsExactly("0:kube");
        assertThat(onEdt(popup::visibleFieldNames)).containsExactly("app", "kube.a", "kube.b", "kube.pod");

        onEdt(() -> popup.setFilterText("POD"));
        assertThat(onEdt(popup::visibleFieldNames)).containsExactly("kube.pod");
        assertThat(onEdt(popup::visibleHeadings)).containsExactly("0:kube");

        onEdt(() -> popup.setFilterText("app2"));
        assertThat(onEdt(popup::visibleFieldNames)).containsExactly("app");
        assertThat(onEdt(popup::visibleHeadings)).isEmpty();
    }

    // --- Per-index state, refreshes and aliases ---

    @Test
    void eachIndexKeepsItsOwnFiltersAcrossRapidSwitches() throws Exception {
        select("A");
        onEdt(() -> panel.narrowFieldValue("app", "app1"));

        select("B");
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEmpty();
        onEdt(() -> {
            panel.narrowFieldValue("app", "app2");
            panel.narrowFieldValue("host", "host3");
        });

        select("A");
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("app", List.of("app1")));
        select("B");
        assertThat(onEdt(() -> panel.draft().fieldFilters()))
                .isEqualTo(Map.of("app", List.of("app2"), "host", List.of("host3")));

        assertThat(saves).containsExactly("A={app=[app1]}", "B={app=[app2], host=[host3]}", "A={app=[app1]}");
        assertThat(store.get("A")).isEqualTo(Map.of("app", List.of("app1")));
        assertThat(store.get("B")).isEqualTo(Map.of("app", List.of("app2"), "host", List.of("host3")));
    }

    @Test
    void queuedReplyForThePreviousIndexDoesNotTouchTheNewOne() throws Exception {
        select("A");
        onEdt(() -> {
            fieldListener.onFieldSummariesUpdated("A", fields("stale"), false);
            panel.selectIndex("B");
            panel.fieldRefreshButton().setRefreshing(true);
        });
        drainEdt();

        assertThat(onEdt(() -> panel.suggestedFieldNames())).containsExactly("app", "host");
        assertThat(onEdt(() -> panel.fieldRefreshButton().isRefreshing())).isTrue();
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"B\"");

        fieldListener.onFieldSummariesUpdated("A", fields("stale"), false);
        drainEdt();
        assertThat(onEdt(() -> panel.suggestedFieldNames())).containsExactly("app", "host");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"B\"");
        assertThat(onEdt(() -> panel.fieldRefreshButton().isRefreshing())).isTrue();
    }

    @Test
    void editsSurviveBothTheCachedAndTheFreshSummary() throws Exception {
        store.put("A", Map.of("pod", List.of("pod1")));
        select("A");
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("pod", List.of("pod1")));

        onEdt(() -> panel.narrowFieldValue("app", "app2"));
        fieldListener.onFieldSummariesUpdated("A", fields("app", "pod", "extra"), true);
        drainEdt();
        assertThat(onEdt(() -> panel.draft().fieldFilters()))
                .isEqualTo(Map.of("pod", List.of("pod1"), "app", List.of("app2")));

        onEdt(() -> {
            panel.narrowLine().removeChip(panel.narrowLine().chips().getFirst());
            panel.narrowFieldValue("app", "app3");
        });
        fieldListener.onFieldSummariesUpdated("A", fields("app", "pod", "extra"), false);
        drainEdt();

        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("app", List.of("app2", "app3")));
        assertThat(onEdt(() -> panel.suggestedFieldNames())).containsExactly("app", "pod", "extra");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"A\" (app=\"app2\" OR app=\"app3\")");
    }

    @Test
    void persistedFiltersAreRestoredWhenFieldsArriveAfterAnEmptyCache() throws Exception {
        store.put("A", Map.of("app", List.of("app1")));
        cachedFields.put("A", new TreeSet<>());
        select("A");
        assertThat(onEdt(() -> panel.suggestedFieldNames())).isEmpty();

        fieldListener.onFieldSummariesUpdated("A", fields("app", "pod"), false);
        drainEdt();

        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("app", List.of("app1")));
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=\"A\" app=\"app1\"");
    }

    @Test
    void twinFieldsShareOneLogicalFieldAndTheAppliedScopeMatchesEither() throws Exception {
        store.put("A", Map.of("k8s_pod_name", List.of("p1")));
        cachedFields.put("A", fields("app", "k8s.pod_name", "k8s_pod_name"));
        select("A");

        assertThat(onEdt(() -> panel.suggestedFieldNames())).containsExactly("app", "k8s.pod_name");
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("k8s.pod_name", List.of("p1")));

        onEdt(() -> panel.apply());

        var captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        Scope scope = captor.getValue();
        assertThat(scope.fieldFilters()).containsExactlyEntriesOf(Map.of("k8s.pod_name", List.of("p1")));
        assertThat(scope.filterFields("k8s.pod_name")).containsExactly("k8s.pod_name", "k8s_pod_name");
        assertThat(SplQuery.scope(scope))
                .isEqualTo("search index=\"A\" (k8s.pod_name=\"p1\" OR k8s_pod_name=\"p1\")");
        assertThat(store.get("A")).isEqualTo(Map.of("k8s.pod_name", List.of("p1")));
    }

    // --- Bookmarks outside the new scope ---

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
        onEdt(() -> panel.narrowFieldValue("app", "app1"));
        AtomicInteger completed = new AtomicInteger();
        onEdt(() -> panel.setOnLoginComplete(sm -> completed.incrementAndGet()));

        onEdt(() -> panel.apply());

        assertThat(fake.events).hasSize(3);
        assertThat(fake.events.get(0)).isEqualTo("outside A {app=[app1]}");
        assertThat(fake.events.get(1)).startsWith("confirm: 3 bookmarks are outside the new scope and will be removed")
                .contains("Copy bookmarks").contains("Export");
        assertThat(fake.events.get(2)).isEqualTo("drop");
        verify(searchManager).setScope(any(Scope.class));
        assertThat(completed).hasValue(1);
    }

    @Test
    void applyWithBookmarksOutsideAndNoStaysInThePanelWithoutSavingOrSettingTheScope() throws Exception {
        FakeBookmarks fake = installBookmarks(2, false);
        select("A");
        onEdt(() -> panel.narrowFieldValue("app", "app1"));
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

    // --- Refresh failures & deepen ---

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
        assertThat(onEdt(() -> panel.suggestedFieldNames())).containsExactly("app", "pod");
    }

    // --- Several indexes or all ---

    @Test
    void selectingSeveralIndexesAndAllIndexesWorksWithPerKeyPersistence() throws Exception {
        cachedFields.put("A,B", fields("app", "host"));
        select("A");
        onEdt(() -> panel.narrowFieldValue("app", "app1"));

        select("A,B");
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEmpty();
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("(index=\"A\" OR index=\"B\")");
        onEdt(() -> panel.narrowFieldValue("app", "both"));

        select("A");
        assertThat(onEdt(() -> panel.draft().fieldFilters())).isEqualTo(Map.of("app", List.of("app1")));
        select("*");
        assertThat(onEdt(() -> panel.generatedQuery())).isEqualTo("index=*");

        select("");
        assertThat(onEdt(() -> panel.generatedQuery())).isEmpty();
    }
}
