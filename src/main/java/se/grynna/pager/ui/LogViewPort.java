package se.grynna.pager.ui;

import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.ui.details.DetailsPanel;

import java.time.Instant;

public interface LogViewPort extends ScrolLockListener {

    /**
     * Open or close the search input bar for typing a search term.
     * If a search is already active, F3 should navigate instead — handled by the caller.
     */
    void setSearchInputActive(boolean b);

    /**
     * Open the search input bar, pre-filled with {@code prefill} when it is a
     * non-blank term (refining an active search). Defaults to a plain prompt.
     */
    default void openSearchPrompt(String prefill) {
        setSearchInputActive(true);
    }

    /**
     * A one-line log selection while the search input is open: it replaces the input's text (all
     * selected) unless the user has typed in the input since it opened. Keyboard focus stays put.
     */
    default void offerSelectionToSearchPrompt(String selected) {}

    /**
     * True while a Y/N question is waiting in the status bar: a boundary
     * expansion prompt or a search-range extend offer. Answers win over the
     * view's own bindings for the shared keys (Y/N/Space/Enter).
     */
    boolean isYesNoPromptShowing();

    /**
     * Toggle into the Matches view (F4 / {@code &} in the log view). With no
     * active search, opens the empty Matches view with the search prompt.
     */
    void toggleMatches();

    /**
     * Show the match under the cursor in the log view (Enter / F4 / {@code &} in
     * the Matches view): an exact jump to the hit's identity with the in-event offset carried over.
     */
    void showMatchInLog();

    /**
     * Leave the event-list view (Matches or Bookmarks) and return to the view and viewport held before
     * entering, without moving it (Esc / Ctrl-G): the Bookmarks view goes back to the view it was opened
     * from, the Matches view to the log.
     *
     * @return true if one of the views was shown and has now been left
     */
    boolean backToLog();

    /**
     * True while the Matches view is shown. Drives the key bindings' context so
     * the Matches-view keys (Enter, F4, Shift-Enter, {@code i}) win over the log view's.
     */
    boolean isMatchesView();

    /** True while the Bookmarks view is shown; its keys (Enter, m, F4, ...) win over the other views'. */
    default boolean isBookmarksView() {
        return false;
    }

    /** Open the Bookmarks view from the log or Matches view ({@code M} / Ctrl-Shift-B). */
    default void showBookmarks() {}

    /**
     * Show or hide the Bookmarks view (F2): in the Bookmarks view it goes back exactly as Esc does
     * ({@link #backToLog()}), elsewhere it opens the view ({@link #showBookmarks()}).
     */
    default void toggleBookmarks() {
        if (isBookmarksView()) {
            backToLog();
        } else {
            showBookmarks();
        }
    }

    /**
     * Copy every bookmarked event, whole and in order, in the Bookmarks view (Ctrl-C without a selection).
     *
     * @return true when this was the Bookmarks view and its events were copied
     */
    default boolean copyAllBookmarks() {
        return false;
    }

    /** Open the Copy as… format menu at the cursor row (Ctrl-Shift-C, and the ☰ menu). */
    default void showCopyAsMenu() {}

    /** True while the user is typing in the search input. */
    default boolean isSearchInputActive() {
        return false;
    }

    void showPreferences();

    /**
     * Toggle the keyboard help overlay: open it when closed, close it when shown.
     */
    void showKeyboardHelp();

    /**
     * Close the keyboard help overlay if it is shown.
     * @return true if the overlay was shown and is now closed
     */
    boolean closeKeyboardHelp();

    LogViewer.CharacterSize getCharacterSize();

    void updateVisibleLines();

    void setHorizontalOffset(int leftOfPage);

    /**
     * Open the inline event details over the current view for the event {@code logLine} belongs to (the line under
     * the cursor), or refresh them when they are open. The view underneath stays mounted with its cursor.
     */
    void openLogLineDetails(LoadedLogLine logLine);

    /**
     * True while the event details are open over a view. Drives the {@link KeyBinding.Context#DETAILS_VIEW} key
     * context and sends the cursor keys to {@link #detailsPanel()}.
     */
    default boolean isDetailsOpen() {
        return false;
    }

    /** The open details panel, or null when the details are closed. */
    default DetailsPanel detailsPanel() {
        return null;
    }

    /**
     * Close the details if they are open (Esc, Ctrl-G, Enter, the DETAILS tab); the view underneath is as it was.
     *
     * @return true if the details were open and are now closed
     */
    default boolean closeDetails() {
        return false;
    }

    /** Show the previous ({@code forward} false) or next event of the view underneath in the details. */
    default void detailsStep(boolean forward) {}

    /** {@code +}: narrow the scope to the selected field's value, or say why not. */
    default void detailsNarrowField() {}

    /** {@code -}: exclude the selected field's value from the scope, or say why not. */
    default void detailsExcludeField() {}

    /** {@code f}: add the selected field to the message fields, or remove it. */
    default void detailsToggleMessageField() {}

    /** Ctrl-C in the details: copy the selected field's value. */
    default void detailsCopyValue() {}

    /** The context-menu key in the details: open the field menu at the selected field. */
    default void showDetailsFieldMenu() {}

    void actionLogin();

    void loginComplete(SearchManager searchManager);

    /**
     * Submit a search query. Starts a background search against Splunk
     * and tracks results in a SearchSession.
     */
    void executeSearch(String searchTerm);

    /**
     * Submit a search query and optionally open the Matches view once the search
     * is bound (Shift-Enter in the prompt, or Enter in the empty Matches view). The
     * default keeps the current behaviour of staying in the log view.
     */
    default void executeSearch(String searchTerm, boolean openMatches) {
        executeSearch(searchTerm);
    }

    /**
     * Navigate to the next search hit, or open the search input bar if no search is active.
     */
    void searchNext();

    /**
     * Navigate to the previous search hit, or open the search input bar if no search is active.
     */
    void searchPrevious();

    /**
     * True when the active search has ended (finished, stopped or failed) without a single match, in the Log or
     * Matches view: there is nothing to step through, so the desktop next/previous keys reopen the prompt.
     */
    default boolean searchEndedWithoutMatches() {
        return false;
    }

    /**
     * The search term of the currently active search, or empty if no search is active.
     * Used by the renderer to highlight matches in the visible lines.
     */
    java.util.Optional<String> getActiveSearchTerm();

    /** The last search term the user submitted, active or not; empty before the first search. */
    default java.util.Optional<String> lastSubmittedSearchTerm() {
        return java.util.Optional.empty();
    }

    /**
     * Terms to highlight in the log lines (case-insensitive): by default the active
     * search term. A viewer may add terms that stay highlighted after the search ended,
     * such as the scope's narrowing terms.
     */
    default java.util.List<String> getHighlightTerms() {
        return getActiveSearchTerm().stream().toList();
    }

    boolean actionAcceptBoundaryExpansion(boolean accepted);

    /** G / R and the time group: open the time panel with {@code focus} focused. */
    default void openTimePanel(se.grynna.pager.ui.time.TimePanel.Focus focus) {}

    /** True while the time panel is open: it keeps the keyboard, so the log's keys do not fire. */
    default boolean isTimePanelOpen() {
        return false;
    }

    /** Closes the time panel without changes; false when it was not open. */
    default boolean closeTimePanel() {
        return false;
    }

    /**
     * Navigate to the specified timestamp in the log.
     * @param timestamp The target timestamp
     */
    void gotoTimestamp(Instant timestamp);

    default void setRange(Instant from, Instant to) {
        throw new UnsupportedOperationException("This viewport has no browsing range");
    }

    /**
     * Cancel search mode (input prompt) and restore normal state.
     * @return true if search mode was active and cancelled, false otherwise
     */
    boolean cancelSearchMode();

    void cancelSearch();

    /**
     * First Esc of a search (Log view): drop the transient search state (the F3 navigation
     * highlight and the end-of-matches note) and show the hint that a second press ends it.
     *
     * @param endKey the label of the key that ends the search, for the hint
     */
    void clearSearchTransient(String endKey);

    /** Bookmark or unbookmark the event under the cursor, or every event the selection touches. */
    default void toggleBookmark() {}

    /**
     * {@code a}: narrow the scope to the cursor row's thread (its process when it has no thread). A click on a row's
     * icon opens the activity popup instead ({@link #showActivityMenu}).
     */
    default void narrowToCursorActivity() {}

    /** {@code A}: narrow the scope to the cursor row's request. */
    default void narrowToCursorRequest() {}

    /** Left-click on a row's icon: open the activity popup (narrow/exclude per level) at the pointer. */
    default void showActivityMenu(int x, int y) {}

    /** Right-click: open the row menu at the pointer ({@code x}, {@code y} in the view's coordinates). */
    default void showRowContextMenu(int x, int y) {}

    /** Shift-F10 and the context-menu key: open the row menu at the cursor row. */
    default void showRowContextMenuAtCursor() {}

    /** Fold the active search's term into the scope as a narrowing term. */
    void narrowScopeToSearch();

    /** Exclude the active search's term from the scope. */
    void excludeSearchFromScope();
}
