package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An update queued on the EDT before a search is cancelled or replaced
 * must not reach the UI once the EDT drains.
 */
class SearchUpdateBindingTest {

    private static final Instant T = Instant.parse("2017-03-30T10:00:00Z");

    /** Stand-in for the EDT: queues posted work until {@link #drain()}. */
    private final Queue<Runnable> edt = new ArrayDeque<>();

    private final SearchUpdateBinding binding = new SearchUpdateBinding(edt::add);
    private final List<String> delivered = new ArrayList<>();

    private void drain() {
        Runnable r;
        while ((r = edt.poll()) != null) {
            r.run();
        }
    }

    private void record(SearchSession session) {
        delivered.add(session.getQuery() + ":" + session.getState() + ":" + session.hitCount());
    }

    @Test
    void boundSessionUpdatesAreDeliveredOnTheEdt() {
        SearchSession session = SearchSessionTestSupport.sessionWith("a", T);
        binding.bind(session, this::record);

        SearchSessionTestSupport.addHit(session, SearchHit.at(T));
        assertThat(delivered).as("nothing runs until the EDT drains").isEmpty();

        drain();
        assertThat(delivered).containsExactly("a:RUNNING:1");
    }

    @Test
    void cancelWithAnUpdateAlreadyQueuedDoesNotRestoreTheOldSummary() {
        SearchSession session = SearchSessionTestSupport.sessionWith("a", T);
        binding.bind(session, this::record);
        SearchSessionTestSupport.addHit(session, SearchHit.at(T));
        session.cancel();
        assertThat(edt).as("updates queued before the UI clears").isNotEmpty();

        binding.unbind();
        drain();

        assertThat(delivered).isEmpty();
        assertThat(binding.isBound(session)).isFalse();
    }

    @Test
    void replacedSearchCannotShowItsHitCountOrErrorAlongsideTheNewQuery() {
        SearchSession old = SearchSessionTestSupport.sessionWith("old", T);
        binding.bind(old, this::record);
        SearchSessionTestSupport.addHit(old, SearchHit.at(T));

        SearchSession replacement = SearchSessionTestSupport.sessionWith("new", T);
        binding.bind(replacement, this::record);
        drain();
        assertThat(delivered).as("queued update of the replaced search").isEmpty();

        // The old listener is detached, so late events do not even queue work.
        SearchSessionTestSupport.fail(old, "boom");
        assertThat(edt).isEmpty();

        SearchSessionTestSupport.complete(replacement);
        drain();
        assertThat(delivered).containsExactly("new:COMPLETED:0");
        assertThat(binding.isBound(replacement)).isTrue();
        assertThat(binding.isBound(old)).isFalse();
    }
}
