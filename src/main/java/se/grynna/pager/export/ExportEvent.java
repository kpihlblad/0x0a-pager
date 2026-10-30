package se.grynna.pager.export;

import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogEventMapper;
import se.grynna.pager.ui.DecoratedLogLine;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Instant;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One event as the exporters see it: whole message, not split into rows.
 *
 * @param displayTime the timestamp shown to the user ({@code msgTime})
 * @param splunkTime  Splunk's {@code _time}
 * @param level       the event level, may be null
 * @param messageLines the message split into lines, never empty
 * @param raw         the event's {@code _raw}
 * @param eventMap    the event's fields, sorted by name
 * @param preamble    the timestamp column exactly as the log view draws it (padded to the preamble width)
 */
public record ExportEvent(Instant displayTime, Instant splunkTime, Level level, List<String> messageLines, String raw,
        SortedMap<String, String> eventMap, String preamble) {

    public ExportEvent {
        messageLines = List.copyOf(messageLines);
        eventMap = eventMap == null ? new TreeMap<>() : new TreeMap<>(eventMap);
        preamble = preamble == null ? "" : preamble;
    }

    /**
     * Builds the export event from an event-level line (as produced by
     * {@link LogEventMapper#mapEventSingleLine}). Message lines are split the same way as the log view's rows.
     */
    public static ExportEvent of(LoadedLogLine eventLine) {
        var displayTime = eventLine.getDisplayTime();
        var preamble = DecoratedLogLine.format(0, DateTimeFormatterManager.formatLogTimestamp(displayTime), "");
        var messageLines = LogEventMapper.splitLines(eventLine).stream().map(LoadedLogLine::getMessage).toList();
        return new ExportEvent(displayTime, eventLine.getSplunkTime(), eventLine.getLevel(), messageLines,
                eventLine.getRaw(), eventLine.getEventMap(), preamble);
    }
}
