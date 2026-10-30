package se.grynna.pager.ui;

import se.grynna.pager.splunk.Activity;

/**
 * The tooltip of a row's icon: one monospace line per known level of the
 * activity, or {@code no activity found}. The text is built from the row already in memory, so it does no I/O.
 */
final class ActivityTooltip {

    static final String NO_ACTIVITY = "no activity found";

    private static final String OPEN = "<html><table cellspacing='0' cellpadding='0'>";

    private ActivityTooltip() {}

    /** HTML tooltip text for {@code activity}; every value is escaped. */
    static String text(Activity activity) {
        if (activity == null || activity.isEmpty()) {
            return "<html>" + NO_ACTIVITY + "</html>";
        }
        StringBuilder html = new StringBuilder(OPEN);
        row(html, "machine", activity.machine());
        row(html, "process", activity.process());
        if (activity.machine() == null && activity.process() == null) {
            row(html, "source", activity.origin());
        }
        row(html, "thread", activity.thread());
        row(html, "request", activity.request());
        return html.append("</table></html>").toString();
    }

    private static void row(StringBuilder html, String label, String value) {
        if (value == null) {
            return;
        }
        html.append("<tr><td><tt>").append(label).append("</tt></td><td><tt>&nbsp;&nbsp;").append(escape(value))
                .append("</tt></td></tr>");
    }

    /** Escapes the characters HTML gives a meaning to. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
