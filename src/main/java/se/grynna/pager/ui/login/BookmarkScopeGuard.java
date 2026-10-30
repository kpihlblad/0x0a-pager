package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.Scope;

/**
 * The index panel's view of the session's bookmarks: how many a scope change would drop, and the
 * dropping itself once the user has confirmed. Implemented by the log viewer.
 */
public interface BookmarkScopeGuard {

    /** How many bookmarks would be outside {@code newScope} if it replaced the current scope. */
    int outside(Scope newScope);

    /** Drop the bookmarks outside {@code newScope}; call before it replaces the current scope. */
    void drop(Scope newScope);
}
