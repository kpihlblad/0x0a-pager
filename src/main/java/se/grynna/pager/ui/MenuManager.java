package se.grynna.pager.ui;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;

public class MenuManager {

    /** Label and enabled state of the Matches entry for the current search and view. */
    public record MatchesItem(String text, boolean enabled) {}

    private final JButton hamburgerButton;
    private final JPopupMenu popupMenu;
    private final JMenuItem matchesItem;
    private final JMenuItem narrowItem;
    private final JMenuItem excludeItem;
    private final JMenuItem bookmarkItem;
    private final JMenuItem narrowActivityItem;
    private final JMenuItem bookmarksViewItem;
    private final JMenuItem copyBookmarksItem;
    private final JMenuItem copyAsItem;
    private final JMenuItem exportItem;
    private final JMenuItem loginItem;
    private final JMenuItem rangeItem;
    private final JMenuItem helpItem;
    private final JCheckBoxMenuItem cursorItem;
    private final JCheckBoxMenuItem lightModeItem;

    public MenuManager(LogViewer viewer) {
        KeyMap keyMap = KeyMap.preferred();
        hamburgerButton = new JButton("☰");
        hamburgerButton.setMargin(new Insets(0, 5, 0, 5));
        hamburgerButton.setFocusable(false); // Don't steal focus - keyboard events should go to main actions
        popupMenu = new JPopupMenu();

        // Menu items call viewer methods directly: the menu is built before the key
        // bindings are installed, and those live on the view panel's action map anyway.
        loginItem = new JMenuItem(loginText(keyMap));
        loginItem.addActionListener(e -> viewer.actionLogin());
        popupMenu.add(loginItem);

        rangeItem = new JMenuItem(rangeText(keyMap));
        rangeItem.addActionListener(e -> viewer.openTimePanel(se.grynna.pager.ui.time.TimePanel.Focus.FROM));
        popupMenu.add(rangeItem);

        // Add other items (Change Index, Search Overview, Help)...

        MatchesItem initial = matchesEntry(false, false, keyMap);
        matchesItem = new JMenuItem(initial.text());
        matchesItem.setEnabled(initial.enabled());
        matchesItem.addActionListener(e -> viewer.toggleMatchesOrShowInLog());
        popupMenu.add(matchesItem);

        narrowItem = new JMenuItem(narrowText(keyMap));
        narrowItem.addActionListener(e -> viewer.narrowScopeToSearch());
        popupMenu.add(narrowItem);

        excludeItem = new JMenuItem(excludeText(keyMap));
        excludeItem.addActionListener(e -> viewer.excludeSearchFromScope());
        popupMenu.add(excludeItem);

        bookmarkItem = new JMenuItem(bookmarkText(keyMap));
        bookmarkItem.addActionListener(e -> viewer.toggleBookmark());
        popupMenu.add(bookmarkItem);

        narrowActivityItem = new JMenuItem(narrowActivityText(keyMap));
        narrowActivityItem.addActionListener(e -> viewer.narrowToCursorActivity());
        popupMenu.add(narrowActivityItem);

        bookmarksViewItem = new JMenuItem(bookmarksViewText(keyMap));
        bookmarksViewItem.addActionListener(e -> viewer.showBookmarks());
        popupMenu.add(bookmarksViewItem);

        copyBookmarksItem = new JMenuItem(copyBookmarksText());
        copyBookmarksItem.setEnabled(false);
        copyBookmarksItem.addActionListener(e -> viewer.copyBookmarks());
        popupMenu.add(copyBookmarksItem);

        copyAsItem = new JMenuItem(copyAsText(keyMap));
        copyAsItem.addActionListener(e -> viewer.showCopyAsMenu());
        popupMenu.add(copyAsItem);

        exportItem = new JMenuItem(exportText());
        exportItem.addActionListener(e -> viewer.showExportDialog());
        popupMenu.add(exportItem);

        cursorItem = new JCheckBoxMenuItem(cursorText(keyMap));
        cursorItem.addActionListener(e -> viewer.toggleCursorMode());
        popupMenu.add(cursorItem);

        // The entries depend on the search and view state and on the key hints preference,
        // so they are refreshed each time the menu opens.
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                KeyMap keyMap = KeyMap.preferred();
                loginItem.setText(loginText(keyMap));
                rangeItem.setText(rangeText(keyMap));
                rangeItem.setEnabled(viewer.canOpenTimePanel());
                narrowItem.setText(narrowText(keyMap));
                excludeItem.setText(excludeText(keyMap));
                helpItem.setText(helpText(keyMap));
                cursorItem.setText(cursorText(keyMap));
                cursorItem.setSelected(viewer.isCursorMode());
                lightModeItem.setSelected(viewer.isLightMode());
                bookmarkItem.setText(bookmarkText(keyMap));
                bookmarkItem.setEnabled(viewer.hasLogLines());
                narrowActivityItem.setText(narrowActivityText(keyMap));
                narrowActivityItem.setEnabled(viewer.hasLogLines());
                bookmarksViewItem.setText(bookmarksViewText(keyMap));
                bookmarksViewItem.setEnabled(viewer.canShowBookmarks());
                copyBookmarksItem.setEnabled(viewer.hasBookmarks());
                copyAsItem.setText(copyAsText(keyMap));
                copyAsItem.setEnabled(viewer.hasLogLines());
                exportItem.setEnabled(viewer.hasLogLines());
                boolean searchActive = viewer.getActiveSearchTerm().isPresent();
                MatchesItem state = matchesEntry(searchActive, viewer.isMatchesView(), keyMap);
                matchesItem.setText(state.text());
                matchesItem.setEnabled(state.enabled());
                narrowItem.setEnabled(searchActive);
                excludeItem.setEnabled(searchActive);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        popupMenu.addSeparator();

        lightModeItem = new JCheckBoxMenuItem("Light mode");
        lightModeItem.addActionListener(e -> viewer.toggleLightMode());
        popupMenu.add(lightModeItem);

        JMenuItem preferencesItem = new JMenuItem("Preferences...");
        preferencesItem.addActionListener(e -> viewer.showPreferences());
        popupMenu.add(preferencesItem);

        helpItem = new JMenuItem(helpText(keyMap));
        helpItem.addActionListener(e -> viewer.showKeyboardHelp());
        popupMenu.add(helpItem);

        hamburgerButton.setComponentPopupMenu(popupMenu);

        hamburgerButton.addActionListener(e -> {
            Dimension menuSize = popupMenu.getPreferredSize();
            int xOffset = hamburgerButton.getWidth() - menuSize.width;
            int yOffset = -menuSize.height;
            popupMenu.show(hamburgerButton, xOffset, yOffset);
        });
    }

    /**
     * The Matches entry: {@code Show matches (F4)} in the log view, {@code Show in log (Enter)}
     * in the Matches view. Usable only while a search is active.
     */
    public static MatchesItem matchesEntry(boolean searchActive, boolean matchesView, KeyMap keyMap) {
        String text = matchesView
                ? "Show in log (" + keyMap.label(UiAction.SHOW_IN_LOG) + ")"
                : "Show matches (" + keyMap.label(UiAction.TOGGLE_MATCHES) + ")";
        return new MatchesItem(text, searchActive);
    }

    /** The scope entry that opens the index and field filter selection. */
    public static String loginText(KeyMap keyMap) {
        return "Scope: index and filters (" + keyMap.label(UiAction.LOGIN) + ")";
    }

    /** The entry that opens the time panel with FROM focused. */
    public static String rangeText(KeyMap keyMap) {
        return "Set time range… (" + keyMap.label(UiAction.SET_RANGE) + ")";
    }

    /** The keyboard help entry. */
    public static String helpText(KeyMap keyMap) {
        return "Keyboard help (" + keyMap.label(UiAction.HELP) + ")";
    }

    /** The cursor mode entry, a checkbox that is ticked while the cursor is shown. */
    public static String cursorText(KeyMap keyMap) {
        return "Cursor mode (" + keyMap.label(UiAction.TOGGLE_CURSOR) + ")";
    }

    /** The bookmark entry: toggles the cursor's event or the selection's events. */
    public static String bookmarkText(KeyMap keyMap) {
        return "Toggle bookmark (" + keyMap.label(UiAction.TOGGLE_BOOKMARK) + ")";
    }

    /** The entry that narrows the scope to the cursor row's activity (its thread, else its process). */
    public static String narrowActivityText(KeyMap keyMap) {
        return "Narrow to activity (" + keyMap.label(UiAction.NARROW_TO_ACTIVITY) + ")";
    }

    /** The entry that opens the Bookmarks view; usable from the log and Matches views. */
    public static String bookmarksViewText(KeyMap keyMap) {
        return "Show bookmarks (" + keyMap.label(UiAction.BOOKMARKS_VIEW) + ")";
    }

    /** The entry that copies every bookmark, in order, as the Bookmarks view shows them; no key of its own. */
    public static String copyBookmarksText() {
        return "Copy bookmarks";
    }

    /** The entry that copies the selection in a chosen format; the format menu opens at the cursor. */
    public static String copyAsText(KeyMap keyMap) {
        return "Copy as… (" + keyMap.label(UiAction.COPY_AS) + ")";
    }

    /** The entry that opens the Export dialog (no key of its own). */
    public static String exportText() {
        return "Export…";
    }

    /** The scope entry that folds the active search into the scope as a narrowing term. */
    public static String narrowText(KeyMap keyMap) {
        return "Narrow scope to matches (" + keyMap.label(UiAction.NARROW) + ")";
    }

    /** The scope entry that excludes the active search's matches from the scope. */
    public static String excludeText(KeyMap keyMap) {
        return "Exclude matches from scope (" + keyMap.label(UiAction.EXCLUDE) + ")";
    }

    public JButton getHamburgerButton() {
        return hamburgerButton;
    }
}
