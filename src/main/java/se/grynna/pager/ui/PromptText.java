package se.grynna.pager.ui;

import java.util.Optional;

/** Text rules for the search input: what a paste becomes and which log selections it takes over. */
final class PromptText {

    /** Longest selection that is offered to the search input. */
    static final int MAX_SELECTION_LENGTH = 200;

    private PromptText() {}

    /** A paste is one line: line breaks become spaces, then the ends are trimmed. */
    static String singleLine(String pasted) {
        return pasted.replaceAll("\\r\\n|\\r|\\n", " ").strip();
    }

    /**
     * The search text a log selection offers, or empty when it is not a one-liner: blank, spanning
     * line breaks or longer than {@link #MAX_SELECTION_LENGTH} after trimming.
     */
    static Optional<String> fromSelection(String selected) {
        if (selected == null) {
            return Optional.empty();
        }
        String text = selected.strip();
        if (text.isEmpty() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || text.length() > MAX_SELECTION_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(text);
    }
}
