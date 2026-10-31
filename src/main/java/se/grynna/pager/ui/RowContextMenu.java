package se.grynna.pager.ui;

import se.grynna.pager.splunk.Activity;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.util.List;

/**
 * The right-click menu of a loaded row: activity levels and the selected text as term rows with Narrow/Exclude
 * buttons, then bookmark, copy, search, narrow or exclude by the active search, and details. Every item calls the
 * viewer's action handler. Built apart from showing it, so it runs headless.
 */
public final class RowContextMenu {

    /** Longest value shown in an item before it is cut with an ellipsis. */
    static final int VALUE_MAX = 24;
    static final String NARROW_LABEL = "+ Narrow";
    static final String EXCLUDE_LABEL = "\u2212 Exclude";

    /** A level the row can be narrowed to, with the value to show for it. */
    record LevelItem(Activity.Level level, String value) {}

    /**
     * What the menu depends on.
     *
     * @param levels         the levels with narrow terms, in menu order
     * @param defaultLevel   the level {@code a} narrows to; its item carries that key
     * @param bookmarked     whether the row's event is bookmarked
     * @param searchMatches  whether a search has matches (the narrow and exclude items show only then)
     * @param selection      the selected one-line term, or null when there is none
     * @param keyMap         where the key hints come from
     */
    record Model(List<LevelItem> levels, Activity.Level defaultLevel, boolean bookmarked, boolean searchMatches,
            String selection, KeyMap keyMap) {
        Model(List<LevelItem> levels, Activity.Level defaultLevel, boolean bookmarked, boolean searchMatches,
                KeyMap keyMap) {
            this(levels, defaultLevel, bookmarked, searchMatches, null, keyMap);
        }
    }

    /** The handlers the items call. */
    interface Actions extends ActivityRows.Actions, ActivityRows.SelectionActions {
        void searchForSelection();

        void toggleBookmark();

        void copy();

        void copyAs();

        void narrowToMatches();

        void excludeMatches();

        void details();
    }

    private RowContextMenu() {}

    static JPopupMenu build(Model model, Actions actions) {
        KeyMap keys = model.keyMap();
        JPopupMenu menu = new JPopupMenu();
        if (model.levels().isEmpty()) {
            JMenuItem none = new JMenuItem("Narrow to activity (no activity found)");
            none.setEnabled(false);
            menu.add(none);
        }
        ActivityRows.build(model.levels(), model.defaultLevel(), keys, actions, model.selection(), actions)
                .forEach(menu::add);
        menu.addSeparator();

        menu.add(item(hinted(model.bookmarked() ? "Remove bookmark" : "Bookmark",
                keys.label(UiAction.TOGGLE_BOOKMARK)), actions::toggleBookmark));
        menu.add(item(hinted("Copy", keys.label(UiAction.COPY)), actions::copy));
        menu.add(item(hinted("Copy as…", keys.label(UiAction.COPY_AS)), actions::copyAs));
        if (model.selection() != null) {
            menu.add(item("Search for selection", actions::searchForSelection));
        }
        if (model.searchMatches()) {
            menu.add(item(hinted("Narrow to matches", keys.label(UiAction.NARROW)), actions::narrowToMatches));
            menu.add(item(hinted("Exclude matches", keys.label(UiAction.EXCLUDE)), actions::excludeMatches));
        }
        menu.addSeparator();
        menu.add(item(hinted("Details", keys.label(UiAction.OPEN_DETAILS)), actions::details));
        return menu;
    }

    /** {@code Narrow to thread scheduler-10 (a)}; only the default level and the request carry a key. */
    static String narrowText(LevelItem item, Model model) {
        return narrowText(item, model.defaultLevel(), model.keyMap());
    }

    /** {@code Narrow to thread scheduler-10 (a)}; only the default level and the request carry a key. */
    static String narrowText(LevelItem item, Activity.Level defaultLevel, KeyMap keyMap) {
        String text = "Narrow to " + item.level().label() + " " + elide(item.value());
        if (item.level() == Activity.Level.REQUEST) {
            return hinted(text, keyMap.label(UiAction.NARROW_TO_REQUEST));
        }
        return item.level() == defaultLevel ? hinted(text, keyMap.label(UiAction.NARROW_TO_ACTIVITY)) : text;
    }

    /** {@code Exclude request 4bf92f3577b34da6}; no key excludes an activity. */
    static String excludeText(LevelItem item) {
        return "Exclude " + item.level().label() + " " + elide(item.value());
    }

    static String searchSelectionText(String selection) {
        return selection == null ? "Search for selection" : "Search for selection \"" + elide(selection) + "\"";
    }

    static String narrowSelectionText(String selection) {
        return "Narrow to selection \"" + elide(selection) + "\"";
    }

    static String excludeSelectionText(String selection) {
        return "Exclude selection \"" + elide(selection) + "\"";
    }

    static String elide(String value) {
        return value.length() <= VALUE_MAX ? value : value.substring(0, VALUE_MAX) + "…";
    }

    static String hinted(String text, String key) {
        return text + " (" + key + ")";
    }

    static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }
}
