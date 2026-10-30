package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HelpOverlayTest {

    @Test
    void listsExactlyTheBoundActions() {
        KeyMap keyMap = KeyMap.defaults();
        InputHandler handler = new InputHandler(
                new CursorManager(mock(LogDataProvider.class), mock(LogViewPort.class)),
                mock(LogViewPort.class));
        Set<UiAction> bound = handler.boundActions();

        HelpOverlay overlay = new HelpOverlay(keyMap, bound);

        assertThat(overlay.actions()).containsExactlyInAnyOrderElementsOf(bound);
        assertThat(overlay.actions()).hasSize(bound.size());
    }

    @Test
    void showsOneColumnPerFlavourWithTheKeysOfThatFlavour() {
        HelpOverlay overlay = new HelpOverlay(KeyMap.defaults(), Set.of(UiAction.NEXT_MATCH));

        assertThat(overlay.cell(UiAction.NEXT_MATCH, KeyFlavour.DESKTOP)).isEqualTo("F3");
        assertThat(overlay.cell(UiAction.NEXT_MATCH, KeyFlavour.PAGER)).isEqualTo("n");
        assertThat(overlay.cell(UiAction.NEXT_MATCH, KeyFlavour.EMACS)).isEqualTo("Ctrl-S");

        List<String> texts = labelTexts(overlay);
        assertThat(texts).contains("Action", "Desktop", "Pager (less)", "Emacs", "Next match", "F3", "n", "Ctrl-S");
    }

    @Test
    void aFlavourWithoutAKeyShowsTheFallbackKeyDimmed() {
        HelpOverlay overlay = new HelpOverlay(KeyMap.defaults(), Set.of(UiAction.COPY, UiAction.PAGE_DOWN));

        assertThat(overlay.hasKey(UiAction.COPY, KeyFlavour.EMACS)).isFalse();
        assertThat(overlay.cell(UiAction.COPY, KeyFlavour.EMACS)).isEqualTo("Ctrl-C");
        assertThat(overlay.cell(UiAction.COPY, KeyFlavour.DESKTOP)).isEqualTo("Ctrl-C");
        assertThat(overlay.cell(UiAction.PAGE_DOWN, KeyFlavour.PAGER)).isEqualTo("Space");
        assertThat(overlay.hasKey(UiAction.PAGE_DOWN, KeyFlavour.PAGER)).isTrue();
    }

    @Test
    void thePreferredFlavourComesFirst() {
        assertThat(new HelpOverlay(KeyMap.defaults(), Set.of()).columns())
                .containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER, KeyFlavour.EMACS);
        assertThat(new HelpOverlay(KeyMap.defaults().withFlavour(KeyFlavour.PAGER), Set.of()).columns())
                .containsExactly(KeyFlavour.PAGER, KeyFlavour.DESKTOP, KeyFlavour.EMACS);
        assertThat(new HelpOverlay(KeyMap.defaults().withFlavour(KeyFlavour.EMACS), Set.of()).columns())
                .containsExactly(KeyFlavour.EMACS, KeyFlavour.DESKTOP, KeyFlavour.PAGER);
    }

    @Test
    void listsTheBookmarkToggleWithCtrlBAndM() {
        HelpOverlay overlay = new HelpOverlay(KeyMap.defaults(), Set.of(UiAction.TOGGLE_BOOKMARK));

        assertThat(overlay.cell(UiAction.TOGGLE_BOOKMARK, KeyFlavour.DESKTOP)).isEqualTo("Ctrl-B");
        assertThat(overlay.cell(UiAction.TOGGLE_BOOKMARK, KeyFlavour.PAGER)).isEqualTo("m");
    }

    @Test
    void listsTheBookmarksViewWithF2AndCtrlShiftBAndCapitalM() {
        HelpOverlay overlay = new HelpOverlay(KeyMap.defaults(), Set.of(UiAction.BOOKMARKS_VIEW));

        assertThat(overlay.cell(UiAction.BOOKMARKS_VIEW, KeyFlavour.DESKTOP)).isEqualTo("F2 / Ctrl-Shift-B");
        assertThat(overlay.cell(UiAction.BOOKMARKS_VIEW, KeyFlavour.PAGER)).isEqualTo("M");
    }

    @Test
    void listsTheCursorToggleWithF7AndVAndScrollLock() {
        HelpOverlay overlay = new HelpOverlay(KeyMap.defaults(), Set.of(UiAction.TOGGLE_CURSOR));

        assertThat(overlay.cell(UiAction.TOGGLE_CURSOR, KeyFlavour.DESKTOP)).isEqualTo("F7 / ScrLk");
        assertThat(overlay.cell(UiAction.TOGGLE_CURSOR, KeyFlavour.PAGER)).isEqualTo("v");
        assertThat(labelTexts(overlay)).contains("Toggle cursor mode");
    }

    @Test
    void thePagerAmpersandIsListedForTheBookmarksViewToo() {
        HelpOverlay overlay = new HelpOverlay(KeyMap.defaults().withFlavour(KeyFlavour.PAGER),
                Set.of(UiAction.SHOW_IN_LOG, UiAction.TOGGLE_MATCHES));

        assertThat(overlay.cell(UiAction.SHOW_IN_LOG, KeyFlavour.PAGER)).isEqualTo("&");
        assertThat(overlay.cell(UiAction.TOGGLE_MATCHES, KeyFlavour.PAGER)).isEqualTo("&");
        assertThat(labelTexts(overlay)).contains("Show this match in the log",
                "Show or hide the matches (in the Bookmarks view: go to the matches)");
    }

    @Test
    void listsTheNarrowToActivityKeysUnderTheActivitySectionAndTheRowMenu() {
        Set<UiAction> actions = Set.of(UiAction.NARROW_TO_ACTIVITY, UiAction.NARROW_TO_REQUEST, UiAction.CONTEXT_MENU);
        HelpOverlay overlay = new HelpOverlay(KeyMap.defaults(), actions);

        assertThat(overlay.actions()).containsExactlyInAnyOrderElementsOf(actions);
        assertThat(overlay.cell(UiAction.NARROW_TO_ACTIVITY, KeyFlavour.DESKTOP)).isEqualTo("a");
        assertThat(overlay.cell(UiAction.NARROW_TO_REQUEST, KeyFlavour.DESKTOP)).isEqualTo("A");
        assertThat(overlay.cell(UiAction.CONTEXT_MENU, KeyFlavour.DESKTOP)).isEqualTo("Shift-F10 / Menu");
        assertThat(labelTexts(overlay)).contains("ACTIVITY", UiAction.NARROW_TO_ACTIVITY.description());
    }

    private static List<String> labelTexts(Container root) {
        List<String> texts = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel label) {
                texts.add(label.getText());
            }
            if (c instanceof Container container) {
                texts.addAll(labelTexts(container));
            }
        }
        return texts;
    }
}
