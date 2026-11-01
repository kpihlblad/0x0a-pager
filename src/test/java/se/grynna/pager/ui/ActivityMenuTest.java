package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Activity.Level;
import se.grynna.pager.ui.RowContextMenu.LevelItem;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityMenuTest {

    private static final KeyMap KEYS = KeyMap.defaults();
    private static final List<LevelItem> ALL_LEVELS = List.of(new LevelItem(Level.THREAD, "scheduler-10"),
            new LevelItem(Level.PROCESS, "casbatch-7f9c"), new LevelItem(Level.REQUEST, "4bf92f3577b34da6"));

    private final List<String> calls = new ArrayList<>();
    private final ActivityRows.Actions actions = new ActivityRows.Actions() {
        @Override
        public void narrowTo(Level level) {
            calls.add("narrow " + level);
        }

        @Override
        public void excludeFrom(Level level) {
            calls.add("exclude " + level);
        }
    };

    @Test
    void listsOneMenuItemRowPerLevelWithANarrowAndAnExcludeButton() {
        JPopupMenu menu = ActivityMenu.build(ALL_LEVELS, Level.THREAD, KEYS, actions);

        assertThat(menu.getComponents()).hasSize(3).allMatch(ActivityRows.Row.class::isInstance);
        assertThat(buttonTooltips(menu)).containsExactly("Narrow to thread scheduler-10 (a)",
                "Exclude thread scheduler-10", "Narrow to process casbatch-7f9c", "Exclude process casbatch-7f9c",
                "Narrow to request 4bf92f3577b34da6 (A)", "Exclude request 4bf92f3577b34da6");
    }

    @Test
    void rowsShowTermsWithoutTypeLabelsAndAccentPills() {
        JPopupMenu menu = ActivityMenu.build(ALL_LEVELS, Level.THREAD, KEYS, actions);

        List<String> labels = Arrays.stream(menu.getComponents()).map(Container.class::cast)
                .flatMap(row -> Arrays.stream(row.getComponents())).filter(JLabel.class::isInstance)
                .map(JLabel.class::cast).map(JLabel::getText).toList();
        assertThat(labels).containsExactly("›", "scheduler-10", "▪", "casbatch-7f9c", "⇄", "4bf92f3577b34da6");
        assertThat(buttons(menu)).allMatch(ActionPill.class::isInstance);
        assertThat(buttons(menu).stream().map(JButton::getForeground)).containsExactly(DarculaColors.NARROW_ACCENT,
                DarculaColors.EXCLUDE_ACCENT, DarculaColors.NARROW_ACCENT, DarculaColors.EXCLUDE_ACCENT,
                DarculaColors.NARROW_ACCENT, DarculaColors.EXCLUDE_ACCENT);
        assertThat(buttons(menu)).allSatisfy(button -> {
            ActionPill pill = (ActionPill) button;
            assertThat(pill.accent()).isEqualTo(pill.getForeground());
            assertThat(pill.getFont()).isEqualTo(Fontalizer.getFont());
            assertThat(pill.isContentAreaFilled()).isFalse();
            assertThat(pill.isBorderPainted()).isFalse();
            assertThat(pill.getMargin()).isEqualTo(new Insets(0, 4, 0, 4));
        });
    }

    @Test
    void everyButtonCallsItsHandler() {
        JPopupMenu menu = ActivityMenu.build(ALL_LEVELS, Level.THREAD, KEYS, actions);

        buttons(menu).forEach(JButton::doClick);

        assertThat(calls).containsExactly("narrow THREAD", "exclude THREAD", "narrow PROCESS", "exclude PROCESS",
                "narrow REQUEST", "exclude REQUEST");
    }

    @Test
    void enterOnARowPressesNarrowUntilLeftOrRightChoosesExclude() {
        ActivityRows.Row row = (ActivityRows.Row) ActivityMenu.build(ALL_LEVELS, Level.THREAD, KEYS, actions)
                .getComponent(0);

        row.doClick();
        row.setArmed(true);
        row.processKeyEvent(new KeyEvent(row, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED),
                new MenuElement[]{row}, MenuSelectionManager.defaultManager());
        row.doClick();
        row.setArmed(true); // a click disarms the item
        row.processKeyEvent(new KeyEvent(row, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED),
                new MenuElement[]{row}, MenuSelectionManager.defaultManager());
        row.doClick();

        assertThat(calls).containsExactly("narrow THREAD", "exclude THREAD", "narrow THREAD");
    }

    @Test
    void theRowHasNoBasicMenuMouseHandlerThatWouldArmOrPressIt() {
        ActivityRows.Row row = firstRow();

        assertThat(row.getMouseListeners()).noneMatch(listener -> isBasicMenuItemHandler(listener));
        assertThat(row.getMenuDragMouseListeners()).noneMatch(listener -> isBasicMenuItemHandler(listener));
    }

    @Test
    void theArmedRowShowsTheFocusedLookOnTheChosenPillOnly() {
        ActivityRows.Row row = firstRow();
        ActionPill narrow = (ActionPill) buttons(row).get(0);
        ActionPill exclude = (ActionPill) buttons(row).get(1);

        row.setArmed(true);
        assertThat(narrow.hasFocusedLook()).isTrue();
        assertThat(exclude.hasFocusedLook()).isFalse();

        row.processKeyEvent(new KeyEvent(row, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED),
                new MenuElement[]{row}, MenuSelectionManager.defaultManager());
        assertThat(narrow.hasFocusedLook()).isFalse();
        assertThat(exclude.hasFocusedLook()).isTrue();

        row.setArmed(false);
        assertThat(exclude.hasFocusedLook()).isFalse();
    }

    @Test
    void anArmedRowPaintsItsPlainBackgroundNotTheSelection() {
        ActivityRows.Row row = firstRow();
        row.setSize(row.getPreferredSize());
        row.setArmed(true);
        BufferedImage image = new BufferedImage(row.getWidth(), row.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            row.paint(g);
        } finally {
            g.dispose();
        }

        assertThat(new Color(image.getRGB(0, 0))).isEqualTo(row.getBackground());
    }

    @Test
    void theRowsShareTheirColumnWidthsSoTheButtonsLineUp() {
        JPopupMenu menu = ActivityMenu.build(ALL_LEVELS, Level.THREAD, KEYS, actions);

        assertThat(Arrays.stream(menu.getComponents()).map(row -> row.getPreferredSize().width).distinct())
                .hasSize(1);
    }

    @Test
    void noActivityGivesOneDisabledItem() {
        JPopupMenu menu = ActivityMenu.build(List.of(), null, KEYS, actions);

        assertThat(texts(menu)).containsExactly("No activity found on this row");
        assertThat(items(menu).getFirst().isEnabled()).isFalse();
    }

    /** Every JButton under {@code root}, recursively, in depth-first order. */
    static List<JButton> buttons(Container root) {
        List<JButton> all = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button) {
                all.add(button);
            }
            if (child instanceof Container container) {
                all.addAll(buttons(container));
            }
        }
        return all;
    }

    /** The tooltips of every JButton under {@code root}, in order. */
    static List<String> buttonTooltips(Container root) {
        return buttons(root).stream().map(JButton::getToolTipText).toList();
    }

    private ActivityRows.Row firstRow() {
        return (ActivityRows.Row) ActivityMenu.build(ALL_LEVELS, Level.THREAD, KEYS, actions).getComponent(0);
    }

    /** The mouse handler of {@code BasicMenuItemUI}, which arms the item on hover and presses it on release. */
    private static boolean isBasicMenuItemHandler(Object listener) {
        return listener.getClass().getName().startsWith("javax.swing.plaf.basic.BasicMenuItemUI");
    }

    private static List<JMenuItem> items(JPopupMenu menu) {
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                .toList();
    }

    private static List<String> texts(JPopupMenu menu) {
        return items(menu).stream().map(JMenuItem::getText).toList();
    }
}
