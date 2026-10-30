package se.grynna.pager.ui;

/**
 * One user action, independent of the keys that trigger it.
 * <p>
 * The canonical key and the aliases for each action live in {@link KeyMap}; help
 * texts and menu labels render them through {@link KeyMap#label(UiAction)} so a
 * binding is defined in exactly one place. Each action also names its section in
 * the keyboard help overlay via {@link #group()}.
 */
public enum UiAction {
    REPAINT("Repaint the view", Group.OTHER),

    CURSOR_UP("Cursor up", Group.NAVIGATION), CURSOR_DOWN("Cursor down", Group.NAVIGATION), CURSOR_LEFT(
            "Cursor left", Group.NAVIGATION), CURSOR_RIGHT("Cursor right", Group.NAVIGATION),

    SELECT_UP("Extend selection up", Group.SELECTION_COPY), SELECT_DOWN("Extend selection down",
            Group.SELECTION_COPY), SELECT_LEFT("Extend selection left", Group.SELECTION_COPY), SELECT_RIGHT(
                    "Extend selection right", Group.SELECTION_COPY),

    CURSOR_UP_FAST("Cursor up 8 lines", Group.NAVIGATION), CURSOR_DOWN_FAST("Cursor down 8 lines",
            Group.NAVIGATION), CURSOR_LEFT_FAST("Scroll left 8 columns", Group.NAVIGATION), CURSOR_RIGHT_FAST(
                    "Scroll right 8 columns", Group.NAVIGATION),

    SELECT_UP_FAST("Extend selection up 8 lines", Group.SELECTION_COPY), SELECT_DOWN_FAST(
            "Extend selection down 8 lines", Group.SELECTION_COPY), SELECT_LEFT_FAST(
                    "Extend selection left 8 columns", Group.SELECTION_COPY), SELECT_RIGHT_FAST(
                            "Extend selection right 8 columns", Group.SELECTION_COPY),

    TOGGLE_CURSOR("Toggle cursor mode", Group.NAVIGATION),

    PAGE_UP("Page up", Group.NAVIGATION), PAGE_DOWN("Page down", Group.NAVIGATION),

    SELECT_PAGE_UP("Extend selection one page up", Group.SELECTION_COPY), SELECT_PAGE_DOWN(
            "Extend selection one page down", Group.SELECTION_COPY),

    SET_MARK("Set the selection mark", Group.SELECTION_COPY), COPY("Copy selection",
            Group.SELECTION_COPY), COPY_AS("Copy the selection in a chosen format", Group.SELECTION_COPY),

    OPEN_DETAILS("Open details for the selected line (in the details: close them)", Group.OTHER),

    LOGIN("Log in / select index", Group.OTHER), GOTO("Go to a time", Group.NAVIGATION), SET_RANGE("Set the time range",
            Group.NAVIGATION), CANCEL("Cancel or close",
                    Group.PROMPTS),

    OPEN_SEARCH("Open the search prompt", Group.SEARCH), OPEN_SEARCH_BACKWARD("Open the search prompt backwards",
            Group.SEARCH),

    NEXT_MATCH("Next match", Group.SEARCH), PREVIOUS_MATCH("Previous match", Group.SEARCH),

    SEARCH_SELECTION_FORWARD("Search forward for the selection", Group.SEARCH), SEARCH_SELECTION_BACKWARD(
            "Search backward for the selection", Group.SEARCH),

    TOGGLE_MATCHES("Show or hide the matches (in the Bookmarks view: go to the matches)", Group.SEARCH), SHOW_IN_LOG(
            "Show this match in the log",
            Group.SEARCH), MATCH_DETAILS("Details for this match", Group.SEARCH),

    NARROW("Narrow scope to matches", Group.SEARCH), EXCLUDE("Exclude matches from scope", Group.SEARCH),

    TOGGLE_BOOKMARK("Toggle bookmark", Group.OTHER), BOOKMARKS_VIEW("Show or hide the bookmarks", Group.OTHER),

    NARROW_TO_ACTIVITY("Narrow to the row's thread (or process)", Group.ACTIVITY), NARROW_TO_REQUEST(
            "Narrow to the row's request", Group.ACTIVITY),

    CONTEXT_MENU("Open the row menu (in the details: the field menu)", Group.OTHER),

    DETAILS_PREVIOUS("Details: previous event", Group.DETAILS), DETAILS_NEXT("Details: next event",
            Group.DETAILS), DETAILS_FIRST("Details: first field", Group.DETAILS), DETAILS_LAST("Details: last field",
                    Group.DETAILS), DETAILS_NARROW_FIELD("Details: narrow the scope to the field's value",
                            Group.DETAILS), DETAILS_EXCLUDE_FIELD(
                                    "Details: exclude the field's value from the scope",
                                    Group.DETAILS), DETAILS_MESSAGE_FIELD(
                                            "Details: add or remove the field in the message", Group.DETAILS),

    HELP("Keyboard help", Group.OTHER),

    PROMPT_YES("Answer yes", Group.PROMPTS), PROMPT_NO("Answer no", Group.PROMPTS);

    /** The section an action is listed under in the keyboard help overlay. */
    public enum Group {
        NAVIGATION, SEARCH, ACTIVITY, DETAILS, SELECTION_COPY, PROMPTS, OTHER
    }

    private final String description;
    private final Group group;

    UiAction(String description, Group group) {
        this.description = description;
        this.group = group;
    }

    /** Short human description, used by the help overlay and menu tooltips. */
    public String description() {
        return description;
    }

    /** The help overlay section this action belongs to. */
    public Group group() {
        return group;
    }
}
