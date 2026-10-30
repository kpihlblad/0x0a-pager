package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.ui.clip.ClipboardAccess;

import java.util.Optional;

/**
 * What the search prompt opens with, in priority order: a one-line log selection; else a
 * one-line system-clipboard text that is new; else the active search term; else nothing.
 * <p>
 * A clipboard text is new when it differs from the clipboard text seen the last time the prompt
 * opened (or, before that, when the user logged in: see {@link #seed()}) and from the last submitted
 * search term. Re-opening the prompt therefore keeps the
 * active term instead of re-offering a clipboard text the user already had.
 */
@Slf4j
final class SearchPrefill {

    private final ClipboardAccess clipboard;
    /** The one-line clipboard text seen when the prompt last opened, or null. */
    private String lastSeenClipboard;
    private boolean seeded;

    SearchPrefill(ClipboardAccess clipboard) {
        this.clipboard = clipboard;
    }

    /**
     * Counts the clipboard text of right now as already seen, so what was copied before the app
     * started (or for the login) is not offered as new. Only the first call has an effect.
     */
    void seed() {
        if (seeded) {
            return;
        }
        seeded = true;
        lastSeenClipboard = readClipboard().orElse(null);
    }

    /**
     * The pre-fill for a prompt that is opening now (null when it opens plain). Remembers the
     * clipboard text it saw, whichever rule won.
     *
     * @param selection     the log's single-row selection text, if any
     * @param activeTerm    the active search term, if a search is active
     * @param lastSubmitted the last search term the user submitted, if any
     */
    Optional<String> open(Optional<String> selection, Optional<String> activeTerm, Optional<String> lastSubmitted) {
        String clipboardText = readClipboard().orElse(null);
        String submitted = lastSubmitted.map(String::strip).orElse(null);
        boolean fresh = clipboardText != null && !clipboardText.equals(lastSeenClipboard)
                && !clipboardText.equals(submitted);
        lastSeenClipboard = clipboardText;

        Optional<String> chosen = selection.flatMap(PromptText::fromSelection);
        if (chosen.isEmpty() && fresh) {
            chosen = Optional.of(clipboardText);
        }
        return chosen.or(() -> activeTerm.filter(term -> !term.isBlank()));
    }

    /** The clipboard's one-line text; a clipboard that cannot be read counts as empty. */
    private Optional<String> readClipboard() {
        try {
            return clipboard.text().flatMap(PromptText::fromSelection);
        } catch (RuntimeException e) {
            log.debug("Clipboard unavailable for the search prompt: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
