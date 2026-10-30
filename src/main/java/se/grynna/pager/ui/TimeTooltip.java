package se.grynna.pager.ui;

import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The tooltip of a row's time column: the full date-time with nanosecond
 * precision in the configured zone, plus the same instant in UTC when that zone is not UTC. The column
 * itself shows only milliseconds. The text is built from the row already in memory, so it does no I/O.
 */
final class TimeTooltip {

    private TimeTooltip() {}

    /** HTML tooltip text for a row's display timestamp; null when the row has none. */
    static String text(Instant instant) {
        if (instant == null) {
            return null;
        }
        ZoneId zone = DateTimeFormatterManager.zoneId();
        String local = DateTimeFormatterManager.formatFullTimestamp(instant, zone);
        if (ZoneOffset.UTC.equals(zone.normalized())) {
            return "<html><tt>" + ActivityTooltip.escape(local) + "</tt></html>";
        }
        String utc = DateTimeFormatterManager.formatFullTimestamp(instant, ZoneOffset.UTC);
        return "<html><tt>" + ActivityTooltip.escape(local) + "</tt><br><tt>" + ActivityTooltip.escape(utc)
                + "</tt></html>";
    }
}
