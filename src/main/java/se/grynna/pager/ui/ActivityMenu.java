package se.grynna.pager.ui;

import se.grynna.pager.splunk.Activity;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.util.List;

/**
 * The popup a left-click on a loaded row's Blockies icon opens: one row per activity level with terms, with
 * Narrow and Exclude buttons — the same rows as in the row context menu.
 * Built apart from showing it, so it runs headless.
 */
public final class ActivityMenu {

    private ActivityMenu() {}

    static JPopupMenu build(List<RowContextMenu.LevelItem> levels, Activity.Level defaultLevel, KeyMap keyMap,
            ActivityRows.Actions actions) {
        JPopupMenu menu = new JPopupMenu();
        if (levels.isEmpty()) {
            JMenuItem none = new JMenuItem("No activity found on this row");
            none.setEnabled(false);
            menu.add(none);
            return menu;
        }
        ActivityRows.build(levels, defaultLevel, keyMap, actions).forEach(menu::add);
        return menu;
    }
}
