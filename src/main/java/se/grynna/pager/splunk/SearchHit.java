package se.grynna.pager.splunk;

import java.time.Instant;

/**
 * Immutable reference to a matched search event.
 * <p>
 * {@code splunkTime} is the coordinate used for Splunk query windows and jump/navigation.
 * {@code displayTime} is what the UI shows (source timestamp, falling back to splunkTime).
 */
public record SearchHit(Instant splunkTime, Instant displayTime, String bkt, String cd) {

    public SearchHit {
        if (splunkTime == null) throw new IllegalArgumentException("splunkTime cannot be null");
        if (displayTime == null) throw new IllegalArgumentException("displayTime cannot be null");
    }

    public static SearchHit from(LoadedLogLine line) {
        return new SearchHit(line.getSplunkTime(), line.getDisplayTime(), line.getBkt(), line.getCb());
    }

    public static SearchHit at(Instant timestamp) {
        return new SearchHit(timestamp, timestamp, null, null);
    }

    public boolean hasIdentity() {
        return bkt != null && cd != null;
    }

    public Identity identity() {
        if (!hasIdentity()) {
            return null;
        }
        return new Identity(bkt, cd);
    }

    public record Identity(String bkt, String cd) {}
}
