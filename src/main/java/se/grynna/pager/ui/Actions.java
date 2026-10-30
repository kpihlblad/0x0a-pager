package se.grynna.pager.ui;

public enum Actions {
    CANCEL,

    SEARCH, SEARCH_NEXT, SEARCH_PREV,

    // up/down
    SCROLL_UP, SCROLL_DOWN, PAGE_UP, PAGE_DOWN,

    // mark / cursor
    MARK_UP, MARK_DOWN, MARK_PAGE_UP, MARK_PAGE_DOWN,

    AM_MARK_ON, AM_MARK_OFF,

    CURSOR_UP, CURSOR_DOWN,

    // left, right, start-of line, end-of-line
    AM_SCROLL_LEFT, AM_SCROLL_RIGHT, AM_SCROLL_START, AM_SCROLL_END, AM_SCROLL_TAB_RIGHT, AM_SCROLL_TAB_LEFT,

    INSPECT, COPY, SAVE,

    UNDO, REDO,

    LOGIN, SEARCH_OVERVIEW, PREFERENCES,

    GOTO
}
