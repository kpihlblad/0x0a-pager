package se.grynna.pager.ui;

import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.SearchManager;

import java.time.Instant;

public abstract class AbstractLogViewPort implements LogViewPort {

    @Override
    public void setSearchInputActive(boolean b) {}

    @Override
    public void toggleMatches() {}

    @Override
    public void showMatchInLog() {}

    @Override
    public boolean backToLog() {
        return false;
    }

    @Override
    public boolean isMatchesView() {
        return false;
    }

    @Override
    public void showPreferences() {}

    @Override
    public void showKeyboardHelp() {}

    @Override
    public boolean closeKeyboardHelp() {
        return false;
    }

    @Override
    public LogViewer.CharacterSize getCharacterSize() {
        return null;
    }

    @Override
    public void updateVisibleLines() {}

    @Override
    public void setHorizontalOffset(int leftOfPage) {}

    @Override
    public void openLogLineDetails(LoadedLogLine logLine) {}

    @Override
    public void actionLogin() {}

    @Override
    public void loginComplete(SearchManager searchManager) {}

    @Override
    public void executeSearch(String searchTerm) {}

    @Override
    public void searchNext() {}

    @Override
    public void searchPrevious() {}

    @Override
    public java.util.Optional<String> getActiveSearchTerm() {
        return java.util.Optional.empty();
    }

    @Override
    public boolean actionAcceptBoundaryExpansion(boolean accepted) {
        return false;
    }

    @Override
    public boolean isYesNoPromptShowing() {
        return false;
    }

    @Override
    public void onScrollLockChanged(boolean scrollLock) {}

    @Override
    public void gotoTimestamp(Instant timestamp) {}

    @Override
    public boolean cancelSearchMode() {
        return false;
    }

    @Override
    public void narrowScopeToSearch() {}

    @Override
    public void excludeSearchFromScope() {}

    @Override
    public void cancelSearch() {
        // No-op
    }

    @Override
    public void clearSearchTransient(String endKey) {}
}
