package se.grynna.pager.ui;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import se.grynna.pager.splunk.*;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DecoratedLogLine is a wrapper around LogLine that adds additional properties for UI rendering.
 * It contains the original LogLine and additional properties for highlighting, selection, and cursor position.
 *
 * The preamble (hash + timestamp) is dynamically sized based on the current locale's timestamp format.
 */
@Data
public class DecoratedLogLine {

    // Dynamically calculated preamble length based on current formatter
    private static int preambleLength = calculatePreambleLength();

    static {
        // Update preamble length when formatter changes
        DateTimeFormatterManager.getInstance().addChangeListener(DecoratedLogLine::updatePreambleLength);
    }

    final LogLine logLine;

    boolean highlighted;
    boolean error;
    /** The row belongs to a bookmarked event; drawn as a teal gutter mark. */
    boolean bookmarked;

    int selectionStart;
    int selectionEnd;
    int cursorPosition;

    // Patterns to highlight across all rows (selection echo, search matches, etc.)
    List<TextHighlight> highlights = new ArrayList<>();

    /** Rule drawn at this row's top edge (Matches view only); null draws nothing. */
    GapRule gapRule;
    /** Dim text drawn after the message; never part of {@link #formattedLine()} or {@link #selected()}. */
    String suffix;

    public DecoratedLogLine(LogLine logLine) {
        this.logLine = logLine;
        this.cursorPosition = -1;
    }

    public int lineLength() {
        if (logLine instanceof LoadedLogLine ll) {
            return getPreambleLength() + ll.getMessage().length();
        }
        return 0;
    }

    public String formattedLine() {
        return switch (logLine) {
            case Placeholder p -> p.message();
            case LoadedLogLine loadedLogLine -> formatLoadedLogLine(loadedLogLine);
            case BeforeBoundaryLine ignored -> "";
            case AfterBoundaryLine ignored -> "";
            case NowBoundaryLine ignored -> "";
            default -> throw new IllegalStateException("Unexpected value: " + logLine);
        };
    }

    private String formatLoadedLogLine(LoadedLogLine loadedLogLine) {
        String tsString;
        if (loadedLogLine.isContinuation()) {
            tsString = "";
        } else {
            // Format the timestamp rounded to millis for log display; the time-column tooltip shows full precision
            var ts = loadedLogLine.getDisplayTime();
            tsString = ts == null ? "" : DateTimeFormatterManager.formatLogTimestamp(ts);
        }

        var msg = loadedLogLine.getMessage();
        var hash = logLine.line();

        return format(hash, tsString, msg);
    }

    public static String format(int hash, String tsString, String msg) {
        return String.format("%-" + preambleLength + "s%s", tsString, msg);
    }

    /**
     * Calculate the maximum preamble length by testing various timestamps.
     * We test different dates and times to account for:
     * - Different month/day lengths in different locales
     * - AM/PM indicators in 12-hour formats (which vary by locale)
     * - Maximum year digits (9999)
     * - Maximum fractional seconds (milliseconds)
     */
    private static int calculatePreambleLength() {
        // Use the log formatter (millis precision) for calculating preamble
        int maxLength = 0;

        // Test various timestamps to find the maximum formatted length
        Instant[] testInstants = {
                // Year 9999, different times to test AM/PM variations
                ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999, ZoneId.systemDefault()).toInstant(),
                ZonedDateTime.of(9999, 12, 31, 11, 59, 59, 999_999_999, ZoneId.systemDefault()).toInstant(),
                ZonedDateTime.of(9999, 12, 31, 0, 0, 0, 999_999_999, ZoneId.systemDefault()).toInstant(),
                ZonedDateTime.of(9999, 12, 31, 12, 0, 0, 999_999_999, ZoneId.systemDefault()).toInstant(),
                // Different months (some locales have longer month names)
                ZonedDateTime.of(9999, 1, 1, 18, 32, 32, 999_999_999, ZoneId.systemDefault()).toInstant(),
                ZonedDateTime.of(9999, 9, 9, 6, 43, 33, 999_999_999, ZoneId.systemDefault()).toInstant(),};

        boolean allWhiteSpace = true;
        for (Instant instant : testInstants) {
            String formatted = DateTimeFormatterManager.formatLogTimestamp(instant);
            maxLength = Math.max(maxLength, formatted.length());

            // last position is white space?
            allWhiteSpace &= (formatted.length() > 0
                    && Character.isWhitespace(formatted.charAt(formatted.length() - 1)));
        }

        if (!allWhiteSpace) {
            maxLength++;
        }

        return maxLength;
    }

    /**
     * Update the preamble length when the formatter changes.
     * This is called automatically when locale/timezone preferences change.
     */
    private static void updatePreambleLength() {
        preambleLength = calculatePreambleLength();
    }

    /**
     * Get the current preamble length.
     */
    public static int getPreambleLength() {
        return preambleLength;
    }

    int getPreambleEnd() {
        return getPreambleLength();
    }

    public String selected() {
        return StringUtils.substring(formattedLine(), selectionStart, selectionEnd);
    }
}
