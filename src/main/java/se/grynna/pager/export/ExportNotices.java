package se.grynna.pager.export;

/** The wording of the copy-as and export notices, kept in one place. */
public final class ExportNotices {

    public static final String NOTHING_TO_COPY = "Nothing to copy";
    public static final String NOTHING_TO_EXPORT = "Nothing to export";

    private ExportNotices() {}

    /** "Copied 12 events as CSV". */
    public static String copied(int events, ExportFormat format) {
        return "Copied " + eventCount(events) + switch (format) {
            case TEXT -> " as shown";
            case MESSAGE -> " (message only)";
            case RAW -> " as raw events";
            default -> " as " + format.displayName();
        };
    }

    /** "Exported 212 events to 0x0a-pager-web-20261025-101500.csv". */
    public static String exported(int events, String fileName) {
        return "Exported " + eventCount(events) + " to " + fileName;
    }

    /** The failure toast of a write that did not succeed. */
    public static String exportFailed(String reason) {
        return "Export failed: " + reason;
    }

    static String eventCount(int events) {
        return events == 1 ? "1 event" : events + " events";
    }
}
