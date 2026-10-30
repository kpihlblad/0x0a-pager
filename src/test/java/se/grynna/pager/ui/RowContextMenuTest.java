package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Activity.Level;
import se.grynna.pager.ui.RowContextMenu.LevelItem;
import se.grynna.pager.ui.RowContextMenu.Model;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowContextMenuTest {

    private static final KeyMap KEYS = KeyMap.defaults();
    private static final List<LevelItem> ALL_LEVELS = List.of(new LevelItem(Level.THREAD, "scheduler-10"),
            new LevelItem(Level.PROCESS, "casbatch-7f9c"), new LevelItem(Level.REQUEST, "4bf92f3577b34da6"));

    private final List<String> calls = new ArrayList<>();
    private final RowContextMenu.Actions actions = new RowContextMenu.Actions() {
        @Override
        public void narrowTo(Level level) {
            calls.add("narrow " + level);
        }

        @Override
        public void excludeFrom(Level level) {
            calls.add("exclude " + level);
        }

        @Override
        public void toggleBookmark() {
            calls.add("bookmark");
        }

        @Override
        public void copy() {
            calls.add("copy");
        }

        @Override
        public void copyAs() {
            calls.add("copyAs");
        }

        @Override
        public void narrowToMatches() {
            calls.add("narrowMatches");
        }

        @Override
        public void excludeMatches() {
            calls.add("excludeMatches");
        }

        @Override
        public void details() {
            calls.add("details");
        }
    };

    @Test
    void listsANarrowAndAnExcludeItemPerLevelWithItsValueAndTheKeysOfThreadAndRequest() {
        JPopupMenu menu = RowContextMenu.build(model(ALL_LEVELS, false, false), actions);

        assertThat(ActivityMenuTest.buttonTooltips(menu)).containsExactly("Narrow to thread scheduler-10 (a)",
                "Exclude thread scheduler-10", "Narrow to process casbatch-7f9c", "Exclude process casbatch-7f9c",
                "Narrow to request 4bf92f3577b34da6 (A)", "Exclude request 4bf92f3577b34da6");
        assertThat(texts(menu)).containsExactly("Bookmark (Ctrl-B)", "Copy (Ctrl-C)", "Copy as… (Ctrl-Shift-C)",
                "Details (Enter)");
    }

    @Test
    void theKeyOfTheNarrowItemFollowsTheDefaultLevel() {
        Model processDefault = new Model(List.of(new LevelItem(Level.PROCESS, "p1")), Level.PROCESS, false, false,
                KEYS);

        assertThat(ActivityMenuTest.buttonTooltips(RowContextMenu.build(processDefault, actions))).first()
                .isEqualTo("Narrow to process p1 (a)");
    }

    @Test
    void longValuesAreCutWithAnEllipsis() {
        String value = "x".repeat(60);

        assertThat(RowContextMenu.narrowText(new LevelItem(Level.THREAD, value), model(ALL_LEVELS, false, false)))
                .isEqualTo("Narrow to thread " + "x".repeat(RowContextMenu.VALUE_MAX) + "… (a)");
        assertThat(RowContextMenu.elide("short")).isEqualTo("short");
    }

    @Test
    void onlyLevelsWithTermsAreListedAndNoneGivesADisabledItem() {
        JPopupMenu some = RowContextMenu.build(model(List.of(new LevelItem(Level.REQUEST, "r1")), false, false),
                actions);
        assertThat(ActivityMenuTest.buttonTooltips(some)).containsExactly("Narrow to request r1 (A)",
                "Exclude request r1");
        assertThat(texts(some)).noneMatch(t -> t.contains("thread"));

        JPopupMenu none = RowContextMenu.build(model(List.of(), false, false), actions);
        assertThat(texts(none)).first().isEqualTo("Narrow to activity (no activity found)");
        assertThat(items(none).getFirst().isEnabled()).isFalse();
        assertThat(items(none).stream().skip(1)).allMatch(JMenuItem::isEnabled);
    }

    @Test
    void theBookmarkItemSaysRemoveForABookmarkedRow() {
        JPopupMenu menu = RowContextMenu.build(model(ALL_LEVELS, true, false), actions);

        assertThat(texts(menu)).contains("Remove bookmark (Ctrl-B)").doesNotContain("Bookmark (Ctrl-B)");
    }

    @Test
    void narrowAndExcludeMatchesShowOnlyWithSearchMatches() {
        assertThat(texts(RowContextMenu.build(model(ALL_LEVELS, false, false), actions)))
                .noneMatch(t -> t.contains("matches"));

        JPopupMenu menu = RowContextMenu.build(model(ALL_LEVELS, false, true), actions);

        assertThat(texts(menu)).contains("Narrow to matches (+)", "Exclude matches (-)");
    }

    @Test
    void thereIsNoSearchForSelectionItemBecauseThatKeyHasNoHandlerYet() {
        assertThat(texts(RowContextMenu.build(model(ALL_LEVELS, false, true), actions)))
                .noneMatch(t -> t.toLowerCase().contains("search"));
    }

    @Test
    void everyItemCallsItsHandler() {
        JPopupMenu menu = RowContextMenu.build(model(ALL_LEVELS, false, true), actions);

        ActivityMenuTest.buttons(menu).forEach(JButton::doClick);
        items(menu).forEach(JMenuItem::doClick);

        assertThat(calls).containsExactly("narrow THREAD", "exclude THREAD", "narrow PROCESS", "exclude PROCESS",
                "narrow REQUEST", "exclude REQUEST", "bookmark", "copy", "copyAs", "narrowMatches", "excludeMatches",
                "details");
    }

    @Test
    void groupsTheItemsWithSeparators() {
        JPopupMenu menu = RowContextMenu.build(model(ALL_LEVELS, false, false), actions);

        long separators = Arrays.stream(menu.getComponents()).filter(JPopupMenu.Separator.class::isInstance).count();
        assertThat(separators).isEqualTo(2);
        assertThat(menu.getComponents()[menu.getComponentCount() - 2]).isInstanceOf(JPopupMenu.Separator.class);
    }

    @Test
    void theKeyHintsFollowThePreferredFlavour() {
        Model pager = new Model(ALL_LEVELS, Level.THREAD, false, false, KEYS.withFlavour(KeyFlavour.PAGER));

        assertThat(texts(RowContextMenu.build(pager, actions))).contains("Bookmark (m)");
    }

    private static Model model(List<LevelItem> levels, boolean bookmarked, boolean searchMatches) {
        return new Model(levels, Level.THREAD, bookmarked, searchMatches, KEYS);
    }

    /** The plain menu items: the activity rows are checked through their buttons. */
    private static List<JMenuItem> items(JPopupMenu menu) {
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance)
                .filter(c -> !(c instanceof ActivityRows.Row)).map(JMenuItem.class::cast).toList();
    }

    private static List<String> texts(JPopupMenu menu) {
        return items(menu).stream().map(JMenuItem::getText).toList();
    }
}
