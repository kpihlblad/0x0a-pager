package se.grynna.pager.ui;

/**
 * Search-status wording. The prompt shows where F3 is; the blue bar shows the
 * same end-of-matches note in a form that can stand alone.
 */
final class SearchStatusText {

    private SearchStatusText() {}

    enum Phase {
        RUNNING, COMPLETE, STOPPED, FAILED
    }

    /**
     * Text after the query in {@code Search: "…" — …}. The match count lives in the Matches tab, so it only
     * appears here inside a position ({@code 3 of 212}); a complete search with matches and no position has
     * nothing to add and gets the empty string.
     *
     * @param matchNumber 1-based index of the selected match, or null if none is selected
     * @param edgeNote    lowercase end note, or null when navigation is not stuck
     */
    static String summary(Integer matchNumber, int hitCount, boolean completed, String edgeNote) {
        return summary(matchNumber, hitCount, completed ? Phase.COMPLETE : Phase.RUNNING, edgeNote);
    }

    static final String TOO_MANY_WITHIN_SECOND = "more than 10,000 matches within a second; narrow the search";

    static String summary(Integer matchNumber, int hitCount, Phase phase, String edgeNote) {
        return summary(matchNumber, hitCount, phase, edgeNote, false, false);
    }

    /**
     * @param truncated                  a side stopped at the match limit; counts gain a "+"
     * @param tooManyMatchesWithinSecond a side gave up: even one second held more matches than the limit
     */
    static String summary(Integer matchNumber, int hitCount, Phase phase, String edgeNote, boolean truncated,
            boolean tooManyMatchesWithinSecond) {
        return summary(matchNumber, hitCount, phase, edgeNote, truncated, tooManyMatchesWithinSecond, false);
    }

    /**
     * @param provisional the count includes locally computed matches that Splunk has not confirmed yet; it
     *                    gains a leading "~" ({@code 3 of ~212}, with truncation {@code 3 of ~212+})
     */
    static String summary(Integer matchNumber, int hitCount, Phase phase, String edgeNote, boolean truncated,
            boolean tooManyMatchesWithinSecond, boolean provisional) {
        Phase shown = phase == null ? Phase.COMPLETE : phase;
        String count = count(hitCount, truncated || tooManyMatchesWithinSecond, provisional);
        if (matchNumber != null && matchNumber > 0) {
            String position = "%d of %s".formatted(matchNumber, count);
            if (edgeNote != null) {
                return position + " · " + edgeNote;
            }
            if (shown == Phase.STOPPED) {
                return position + " · stopped";
            }
            return position;
        }
        if (edgeNote != null) {
            return edgeNote;
        }
        if (tooManyMatchesWithinSecond) {
            return TOO_MANY_WITHIN_SECOND;
        }
        return switch (shown) {
            case RUNNING -> "searching…";
            case STOPPED -> "stopped";
            case FAILED -> "search failed";
            case COMPLETE -> hitCount == 0 ? "no matches" : "";
        };
    }

    static String count(int hitCount, boolean truncated) {
        return count(hitCount, truncated, false);
    }

    static String count(int hitCount, boolean truncated, boolean provisional) {
        return (provisional ? "~" : "") + String.format(java.util.Locale.ROOT, "%,d", hitCount)
                + (truncated ? "+" : "");
    }

    /** Blue-bar text. The end note is capitalized because it leads the line. */
    static String center(String edgeNote, String range) {
        if (edgeNote == null || edgeNote.isEmpty()) {
            return range;
        }
        String label = Character.toUpperCase(edgeNote.charAt(0)) + edgeNote.substring(1);
        if (range == null || range.isEmpty()) {
            return label;
        }
        return label + "  ·  " + range;
    }
}
