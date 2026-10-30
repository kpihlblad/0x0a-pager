package se.grynna.pager.splunk;

import java.time.Instant;

/** Seeds a {@link SearchSession} from tests outside the splunk package. */
public final class SearchSessionTestSupport {

    private SearchSessionTestSupport() {}

    public static SearchSession sessionWith(String query, Instant origin, SearchHit... hits) {
        SearchSession session = new SearchSession(query, origin);
        for (SearchHit hit : hits) {
            session.addHit(hit);
        }
        return session;
    }

    public static void addHit(SearchSession session, SearchHit hit) {
        session.addHit(hit);
    }

    /** Record a match with its mapped line, exactly as {@link Searcher} does. */
    public static void addMatch(SearchSession session, SearchHit hit, LoadedLogLine line) {
        long run = session.beginRun();
        session.addMatch(run, hit, line);
    }

    /**
     * Publish a chunk as a {@link Searcher} worker does: the lines and the covered
     * [{@code start}, {@code end}) window together, with one listener notification.
     */
    public static void commitChunk(SearchSession session, java.util.List<LoadedLogLine> lines, Instant start,
            Instant end) {
        session.commitChunk(session.beginRun(), lines, start, end, 10_000);
    }

    /** Simulate a range extension: start a new run on a finished session and add a hit to it. */
    public static void extendWith(SearchSession session, SearchHit hit) {
        long run = session.beginRun();
        session.addHit(run, hit);
    }

    /** Mark a side as stopped at the match limit (in a fresh run of the session). */
    public static void markTruncated(SearchSession session, SearchSession.Side side) {
        session.markTruncated(session.beginRun(), side);
    }

    /** Start a new run on the session, as {@link Searcher} does when it is created for an extension. */
    public static void beginRun(SearchSession session) {
        session.beginRun();
    }

    public static void complete(SearchSession session) {
        session.setState(SearchSession.State.COMPLETED);
    }

    public static void cancel(SearchSession session) {
        session.setState(SearchSession.State.CANCELLED);
    }

    /** Record that [{@code start}, {@code end}] has been searched. */
    public static void coverWindow(SearchSession session, Instant start, Instant end) {
        session.coverWindow(start, end);
    }

    public static void fail(SearchSession session, String message) {
        session.setError(message);
    }
}
