package se.grynna.pager.splunk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Search facade wiring, without opening a connection or a database. */
class SearchManagerSearchTest {
    private static final Instant T = Instant.parse("2017-03-30T10:00:00.123456789Z");
    private SearchManager manager;
    private final List<LoadDecision> decisions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        manager = mock(SearchManager.class, CALLS_REAL_METHODS);
        SplunkClient client = mock(SplunkClient.class);
        SearchBoundaries boundaries = new SearchBoundaries(Clock.fixed(T.plusSeconds(3600), ZoneOffset.UTC));
        boundaries.reset(T.minusSeconds(30), T.plusSeconds(30));
        setField("splunkClient", client);
        setField("searchBoundaries", boundaries);
        setField("EVENT_MAPPER", new LogEventMapper());
        setField("dataProvider", mock(SplunkLogDataProvider.class));
        manager.setScope(Scope.manual(null, "index=test"));
        when(client.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            decisions.add(invocation.getArgument(0));
            return Stream.empty();
        });
    }

    private void setField(String name, Object value) throws Exception {
        Field field = SearchManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    private SearchSession finishedSearch(Instant origin) {
        Searcher searcher = manager.search("boom", origin);
        searcher.start();
        SearchSession session = searcher.getSession();
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        return session;
    }

    @Test
    void newSearchStartsUncoveredAndOriginIsClampedWithNanosecondPrecision() {
        Searcher low = manager.search("boom", T.minusSeconds(1000));
        assertThat(low.getSession().getSearchOrigin()).isEqualTo(T.minusSeconds(30));
        assertThat(low.getSession().windowStart()).isNull();
        assertThat(low.getSession().windowEnd()).isNull();
        Searcher high = manager.search("boom", T.plusSeconds(1000));
        assertThat(high.getSession().getSearchOrigin()).isEqualTo(T.plusSeconds(30));
        assertThat(low.getSession().isDiscarded()).isTrue();
        Searcher defaultSearch = manager.search("boom");
        assertThat(defaultSearch.getSession().getSearchOrigin()).isEqualTo(T.plusSeconds(30));
        manager.cancelSearch();
    }

    @Test
    void explicitOriginSplitsTheWindowAtExactlyTheAnchor() {
        SearchSession session = finishedSearch(T.plusNanos(7));
        assertThat(decisions).hasSize(2);
        assertThat(decisions.stream().filter(d -> d.direction() < 0).findFirst().orElseThrow().end())
                .isEqualTo(T.plusNanos(7));
        assertThat(decisions.stream().filter(d -> d.direction() > 0).findFirst().orElseThrow().start())
                .isEqualTo(T.plusNanos(7));
        assertThat(session.windowStart()).isEqualTo(T.minusSeconds(30));
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(30));
    }

    @Test
    void extendsOnlyTheAdjacentEdgeAndIgnoresStaleRequestsAndGaps() {
        SearchSession session = finishedSearch(T);
        decisions.clear();
        manager.extendSearch(session, T.plusSeconds(30), T.plusSeconds(90));
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.getFirst().start()).isEqualTo(T.plusSeconds(30));
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(90));
        decisions.clear();
        manager.extendSearch(session, T.plusSeconds(100), T.plusSeconds(120));
        assertThat(decisions).isEmpty();
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(90));
        manager.extendSearch(session, T.minusSeconds(90), T.minusSeconds(30));
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        assertThat(session.windowStart()).isEqualTo(T.minusSeconds(90));
        manager.search("replacement", T);
        decisions.clear();
        manager.extendSearch(session, T.plusSeconds(90), T.plusSeconds(120));
        assertThat(decisions).isEmpty();
        manager.cancelSearch();
    }

    @Test
    void truncatedEdgeCannotBeExtendedAsThoughFullySearched() {
        SearchSession session = finishedSearch(T);
        long run = session.beginRun();
        session.markTruncated(run, SearchSession.Side.LATER);
        session.complete(run);
        decisions.clear();
        manager.extendSearch(session, T.plusSeconds(30), T.plusSeconds(90));
        assertThat(session.getState()).isEqualTo(SearchSession.State.COMPLETED);
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(30));
        assertThat(decisions).isEmpty();
    }

    @Test
    void recentreOutsideLogWindowIncludesRequestedTargetWithoutMovingLogBoundaries() {
        Instant target = T.minusSeconds(1000).plusNanos(7);
        Searcher searcher = manager.searchAround("boom", target);
        assertThat(searcher.getSession().getSearchOrigin()).isEqualTo(target);
        searcher.start();
        await().atMost(3, SECONDS).until(() -> searcher.getSession().getState() == SearchSession.State.COMPLETED);
        assertThat(searcher.getSession().windowStart()).isEqualTo(target.minusSeconds(60));
        assertThat(searcher.getSession().windowEnd()).isEqualTo(T.plusSeconds(30));
        Searcher ordinary = manager.search("boom", target);
        assertThat(ordinary.getSession().getSearchOrigin()).isEqualTo(T.minusSeconds(30));
        manager.cancelSearch();
    }

    @Test
    void recentreClampsToAbsoluteSearchLimits() {
        Searcher old = manager.searchAround("boom", Instant.MIN);
        assertThat(old.getSession().getSearchOrigin()).isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY);
        Searcher future = manager.searchAround("boom", Instant.MAX);
        assertThat(future.getSession().getSearchOrigin()).isEqualTo(manager.latestSearchableInstant());
        manager.cancelSearch();
    }
}
