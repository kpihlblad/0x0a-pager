package se.grynna.pager.splunk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.db.ScopeTerms;
import se.grynna.pager.db.SearchFilterManager;
import se.grynna.pager.exceptions.StorageException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The scope is the baseline of every load and search; changing it is a provider generation change. */
class SearchManagerScopeTest {
    private static final Instant T = Instant.parse("2017-03-30T10:00:00Z");
    private SearchManager manager;
    private SplunkLogDataProvider provider;
    private SearchFilterManager filterManager;

    @BeforeEach
    void setUp() throws Exception {
        manager = mock(SearchManager.class, CALLS_REAL_METHODS);
        provider = mock(SplunkLogDataProvider.class);
        filterManager = mock(SearchFilterManager.class);
        SearchBoundaries boundaries = new SearchBoundaries(Clock.fixed(T.plusSeconds(3600), ZoneOffset.UTC));
        boundaries.reset(T.minusSeconds(30), T.plusSeconds(30));
        setField("dataProvider", provider);
        setField("searchFilterManager", filterManager);
        setField("splunkClient", mock(SplunkClient.class));
        setField("searchBoundaries", boundaries);
        setField("EVENT_MAPPER", new LogEventMapper());
    }

    private void setField(String name, Object value) throws Exception {
        Field field = SearchManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    @Test
    void setScopeRendersTheBaselineAndExposesTheScope() {
        Scope scope = Scope.of("web", Map.of("host", "a1")).withNarrow("timeout").withExclude("noise");
        manager.setScope(scope);

        assertThat(manager.scope()).isEqualTo(scope);
        assertThat(manager.getBaselineQuery()).isEqualTo(
                "search index=\"web\" host=\"a1\" | search \"timeout\" | search NOT \"noise\"");
    }

    @Test
    void searchesBuildOnTheScopeRendering() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        setField("splunkClient", client);
        List<String> queries = new CopyOnWriteArrayList<>();
        when(client.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            queries.add(invocation.getArgument(2));
            return Stream.empty();
        });

        manager.setScope(Scope.of("web", Map.of("host", "a1")).withNarrow("timeout").withExclude("noise"));
        Searcher searcher = manager.search("boom", T);
        searcher.start();
        await().atMost(3, SECONDS).until(() -> searcher.getSession().getState() == SearchSession.State.COMPLETED);

        // The search term is one more stage after the scope's stages, never a part of them
        assertThat(queries).isNotEmpty().allSatisfy(q -> assertThat(q).isEqualTo(
                "search index=\"web\" host=\"a1\" | search \"timeout\" | search NOT \"noise\" | search \"boom\""));
    }

    @Test
    void changingTheScopeCancelsTheSearchAndDiscardsTheLoadedLog() {
        manager.setScope(Scope.of("web", Map.of()));
        Searcher searcher = manager.search("boom", T);
        SearchSession session = searcher.getSession();
        assertThat(manager.getActiveSearchSession()).isSameAs(session);

        manager.setScope(Scope.of("web", Map.of()).withNarrow("boom"));

        assertThat(session.isDiscarded()).isTrue();
        assertThat(manager.getActiveSearchSession()).isNull();
        verify(provider, org.mockito.Mockito.times(2)).discardLoadedLog();
    }

    @Test
    void anEquivalentScopeChangesNothingButTheStoredValue() {
        manager.setScope(Scope.of("web", Map.of("host", "a1")));
        Searcher searcher = manager.search("boom", T);

        Scope same = Scope.of("web", Map.of("host", "a1"));
        manager.setScope(same);

        assertThat(searcher.getSession().isDiscarded()).isFalse();
        assertThat(manager.getActiveSearchSession()).isSameAs(searcher.getSession());
        // Only the first (initial) scope reset the provider
        verify(provider, org.mockito.Mockito.times(1)).discardLoadedLog();
        manager.cancelSearch();
    }

    @Test
    void firstScopeOfAFreshManagerResetsTheProviderOnce() {
        verify(provider, never()).discardLoadedLog();
        manager.setScope(Scope.manual(null, "index=test"));
        verify(provider).discardLoadedLog();
        assertThat(manager.getBaselineQuery()).isEqualTo("search index=test");
    }

    @Test
    void setMessageFieldsChangesTheMapperAndDiscardsOnlyWhenTheEffectiveFieldsChange() {
        manager.setScope(Scope.of("web", Map.of()));
        Searcher searcher = manager.search("boom", T);
        org.mockito.Mockito.clearInvocations(provider);

        assertThat(manager.setMessageFields(List.of("msg"))).isTrue();
        assertThat(manager.messageFields()).containsExactly("msg");
        assertThat(searcher.getSession().isDiscarded()).isTrue();
        assertThat(manager.getActiveSearchSession()).isNull();
        verify(provider).discardLoadedLog();

        assertThat(manager.setMessageFields(List.of("msg"))).isFalse();
        assertThat(manager.setMessageFields(null)).isTrue();
        assertThat(manager.messageFields()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
        verify(provider, org.mockito.Mockito.times(2)).discardLoadedLog();
    }

    @Test
    void emptyMessageFieldsAreAnEffectiveSettingAndNullIsTheDefault() {
        assertThat(manager.setMessageFields(List.of())).isTrue();
        assertThat(manager.messageFields()).isEmpty();
        assertThat(manager.setMessageFields(List.of())).isFalse();
        assertThat(manager.setMessageFields(null)).isTrue();
        assertThat(manager.messageFields()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
    }

    @Test
    void settingTheScopeOfAnotherIndexLoadsItsMessageFieldsBeforeTheReload() throws Exception {
        when(filterManager.getMessageFields("web")).thenReturn(Optional.of(List.of("title")));
        when(filterManager.getMessageFields("api")).thenReturn(Optional.empty());

        manager.setScope(Scope.of("web", Map.of()));
        assertThat(manager.messageFields()).containsExactly("title");

        manager.setScope(Scope.of("web", Map.of("host", "a1")));
        assertThat(manager.messageFields()).containsExactly("title");
        verify(filterManager, org.mockito.Mockito.times(1)).getMessageFields("web");

        // A different index with no saved fields returns to the defaults, in the same generation change
        org.mockito.Mockito.clearInvocations(provider);
        manager.setScope(Scope.of("api", Map.of()));
        assertThat(manager.messageFields()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
        verify(provider).discardLoadedLog();
    }

    @Test
    void theFirstScopeOfAnIndexWithSavedFieldsIsOneGenerationChange() throws Exception {
        when(filterManager.getMessageFields("web")).thenReturn(Optional.of(List.of("title")));

        manager.setScope(Scope.of("web", Map.of()));

        verify(provider, org.mockito.Mockito.times(1)).discardLoadedLog();
        assertThat(manager.messageFields()).containsExactly("title");
    }

    @Test
    void aFailedReadOfSavedMessageFieldsUsesTheDefaults() throws Exception {
        when(filterManager.getMessageFields("web")).thenThrow(new StorageException("broken"));

        manager.setScope(Scope.of("web", Map.of()));

        assertThat(manager.messageFields()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
        assertThat(manager.getBaselineQuery()).isEqualTo("search index=\"web\"");
    }

    @Test
    void messageFieldsGoThroughTheFilterManager() throws Exception {
        when(filterManager.getMessageFields("web")).thenReturn(Optional.of(List.of("title")));
        when(filterManager.storeMessageFields("web", List.of("title")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(manager.getMessageFields("web")).contains(List.of("title"));
        assertThat(manager.storeMessageFields("web", List.of("title"))).isCompleted();
        verify(filterManager).storeMessageFields("web", List.of("title"));
    }

    @Test
    void scopeTermsGoThroughTheFilterManager() throws Exception {
        ScopeTerms terms = new ScopeTerms(List.of("a"), List.of("b"));
        when(filterManager.getScopeTerms("web")).thenReturn(terms);
        when(filterManager.storeScopeTerms("web", terms)).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(manager.getScopeTerms("web")).isEqualTo(terms);
        assertThat(manager.storeScopeTerms("web", terms)).isCompleted();
        verify(filterManager).storeScopeTerms("web", terms);
    }
}
