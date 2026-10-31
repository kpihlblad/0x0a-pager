package se.grynna.pager.ui;

import se.grynna.pager.splunk.Activity;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.MenuKeyEvent;
import javax.swing.event.MenuKeyListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The activity rows of the icon popup and the row context menu: one menu
 * item per level — glyph and value — with rounded {@code + Narrow} / {@code − Exclude} buttons, the columns
 * aligned across the rows. The row context menu can append the selected text as one more term row.
 *
 * <p>
 * Each row is a {@link JMenuItem}, so the popup's own keyboard navigation reaches it: Up/Down select a row,
 * Left/Right choose its Narrow or Exclude button (shown as hovered), Enter presses the chosen one, Esc closes. A
 * click on a button presses it; a click elsewhere on the row presses the chosen one (Narrow unless Left/Right chose
 * Exclude). Built apart from showing it, so it runs headless.
 */
public final class ActivityRows {

    /** The handlers the buttons call. */
    interface Actions {
        void narrowTo(Activity.Level level);

        void excludeFrom(Activity.Level level);
    }

    /** The handlers of the selected-text row in the row context menu. */
    interface SelectionActions {
        void narrowToSelection();

        void excludeSelection();
    }

    private ActivityRows() {}

    /** One row per level, in the given order. */
    static List<Row> build(List<RowContextMenu.LevelItem> levels, Activity.Level defaultLevel, KeyMap keyMap,
            Actions actions) {
        return build(levels, defaultLevel, keyMap, actions, null, null);
    }

    /** One row per level, followed by the selected text when present. */
    static List<Row> build(List<RowContextMenu.LevelItem> levels, Activity.Level defaultLevel, KeyMap keyMap,
            Actions actions, String selection, SelectionActions selectionActions) {
        Font font = UIManager.getFont("MenuItem.font");
        List<Row> rows = new ArrayList<>();
        for (RowContextMenu.LevelItem item : levels) {
            rows.add(new Row(item, defaultLevel, keyMap, actions, font));
        }
        if (selection != null) {
            rows.add(new Row(selection, selectionActions, font));
        }
        alignColumns(rows);
        return rows;
    }

    /** Give each label column the width of its widest cell, so every row has the same layout. */
    private static void alignColumns(List<Row> rows) {
        for (int column = 0; column < Row.LABEL_COLUMNS; column++) {
            int width = 0;
            for (Row row : rows) {
                width = Math.max(width, row.labels[column].getPreferredSize().width);
            }
            for (Row row : rows) {
                JLabel label = row.labels[column];
                Dimension size = new Dimension(width, label.getPreferredSize().height);
                label.setPreferredSize(size);
                label.setMinimumSize(size);
            }
        }
    }

    /** The level's glyph: THREAD › (U+203A), PROCESS ▪ (U+25AA), REQUEST ⇄ (U+21C4), anything else · (U+00B7). */
    static String glyph(Activity.Level level) {
        return switch (level) {
            case THREAD -> "\u203A";
            case PROCESS -> "\u25AA";
            case REQUEST -> "\u21C4";
            default -> "\u00B7";
        };
    }

    /** One term: a menu item laid out as glyph, value, Narrow, Exclude. */
    static final class Row extends JMenuItem {

        static final int LABEL_COLUMNS = 2;

        private final JLabel[] labels;
        private final ActionPill narrow;
        private final ActionPill exclude;
        private boolean excludeChosen;

        private Row(RowContextMenu.LevelItem item, Activity.Level defaultLevel, KeyMap keyMap, Actions actions,
                Font font) {
            this("activity " + item.level(), glyph(item.level()), item.value(),
                    RowContextMenu.narrowText(item, defaultLevel, keyMap), RowContextMenu.excludeText(item),
                    () -> actions.narrowTo(item.level()), () -> actions.excludeFrom(item.level()), font);
            narrow.setName("narrow " + item.level());
            exclude.setName("exclude " + item.level());
        }

        private Row(String selection, SelectionActions actions, Font font) {
            this("selection", "", selection, RowContextMenu.narrowSelectionText(selection),
                    RowContextMenu.excludeSelectionText(selection), actions::narrowToSelection,
                    actions::excludeSelection, font);
            narrow.setName("narrow selection");
            exclude.setName("exclude selection");
        }

        private Row(String name, String glyph, String value, String narrowTooltip, String excludeTooltip,
                Runnable narrowAction, Runnable excludeAction, Font font) {
            setLayout(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 8));
            setName(name);
            if (font != null) {
                setFont(font);
            }
            labels = new JLabel[]{label(glyph, DarculaColors.GREY),
                    label(RowContextMenu.elide(value), DarculaColors.FOREGROUND)};
            // On the row, not the label: a label with a tooltip would take the row's clicks.
            setToolTipText(value);
            narrow = button(RowContextMenu.NARROW_LABEL, DarculaColors.MIDNIGHT_BLUE, narrowTooltip, narrowAction);
            exclude = button(RowContextMenu.EXCLUDE_LABEL, DarculaColors.EXCLUDE_RED, excludeTooltip, excludeAction);

            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(0, 0, 0, 8);
            for (int column = 0; column < LABEL_COLUMNS; column++) {
                c.gridx = column;
                c.weightx = column == LABEL_COLUMNS - 1 ? 1 : 0;
                add(labels[column], c);
            }
            c.weightx = 0;
            c.gridx = LABEL_COLUMNS;
            c.insets = new Insets(0, 0, 0, 4);
            add(narrow, c);
            c.gridx = LABEL_COLUMNS + 1;
            c.insets = new Insets(0, 0, 0, 0);
            add(exclude, c);

            addActionListener(e -> chosen().doClick(0));
            addMenuKeyListener(new MenuKeyListener() {
                @Override
                public void menuKeyPressed(MenuKeyEvent e) {
                    int key = e.getKeyCode();
                    if (isArmed() && (key == KeyEvent.VK_LEFT || key == KeyEvent.VK_RIGHT)) {
                        choose(!excludeChosen);
                        e.consume();
                    }
                }

                @Override
                public void menuKeyReleased(MenuKeyEvent e) {}

                @Override
                public void menuKeyTyped(MenuKeyEvent e) {}
            });
        }

        /** The button Enter or a click on the row presses. */
        JButton chosen() {
            return excludeChosen ? exclude : narrow;
        }

        /** Choose Exclude ({@code true}) or Narrow, as Left/Right do. */
        void choose(boolean chooseExclude) {
            excludeChosen = chooseExclude;
            showChoice();
        }

        @Override
        public void menuSelectionChanged(boolean isIncluded) {
            super.menuSelectionChanged(isIncluded);
            showChoice();
        }

        private void showChoice() {
            boolean armed = isArmed();
            narrow.getModel().setRollover(armed && !excludeChosen);
            exclude.getModel().setRollover(armed && excludeChosen);
        }

        /** The layout's size: the menu item UI would size the item by its (empty) text. */
        @Override
        public Dimension getPreferredSize() {
            return isPreferredSizeSet() ? super.getPreferredSize() : getLayout().preferredLayoutSize(this);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private JLabel label(String text, Color color) {
            JLabel label = new JLabel(text);
            label.setForeground(color);
            label.setFont(getFont());
            return label;
        }

        private ActionPill button(String text, Color accent, String tooltip, Runnable action) {
            ActionPill button = new ActionPill(text, accent);
            button.setToolTipText(tooltip);
            button.addActionListener(e -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                JPopupMenu popup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, this);
                if (popup != null) {
                    popup.setVisible(false);
                }
                action.run();
            });
            return button;
        }
    }
}
