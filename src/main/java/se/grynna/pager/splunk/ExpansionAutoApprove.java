package se.grynna.pager.splunk;

import java.time.Duration;
import java.time.Instant;

/**
 * When a range extension is approved without asking: the work that needed it started only a moment ago.
 * <p>
 * The log's boundary expansion ({@link LoadingStrategy}) measures from the first unloaded row it needed; a text
 * search's extension offer (the UI's {@code LogViewer}) measures from the start of the search or its last answered
 * question. Both use this one threshold so they feel alike.
 */
public final class ExpansionAutoApprove {

    /** How long after its start a load or search may keep extending itself before it has to ask. */
    public static final Duration WINDOW = Duration.ofSeconds(10);

    private ExpansionAutoApprove() {}

    /** True when {@code now} is still within {@link #WINDOW} of {@code start}; false without a start. */
    public static boolean shortSince(Instant start, Instant now) {
        if (start == null || now == null) {
            return false;
        }
        Duration elapsed = Duration.between(start, now);
        return !elapsed.isNegative() && elapsed.compareTo(WINDOW) < 0;
    }
}
