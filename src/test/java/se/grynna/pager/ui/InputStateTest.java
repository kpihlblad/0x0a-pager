package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputStateTest {

    @Test
    void scrollTextRendersKeysFromTheKeyMap() {
        KeyMap keyMap = KeyMap.defaults();
        String scroll = InputState.SCROLL.text(keyMap);

        assertThat(scroll).contains(keyMap.label(UiAction.NEXT_MATCH) + " next match");
        assertThat(scroll).contains(keyMap.label(UiAction.OPEN_SEARCH) + " to search");
        assertThat(scroll).contains(keyMap.label(UiAction.TOGGLE_CURSOR) + " cursor mode");
        assertThat(scroll).contains(keyMap.label(UiAction.HELP));
    }

    @Test
    void noStateLeavesAnUnreplacedToken() {
        KeyMap keyMap = KeyMap.defaults();
        for (InputState state : InputState.values()) {
            String rendered = state.text(keyMap, "x");
            assertThat(rendered).as("state %s leaves no token", state).doesNotContain("{").doesNotContain("}");
        }
    }

    @Test
    void yesNoPromptsShowUppercaseYAndN() {
        KeyMap keyMap = KeyMap.defaults();

        assertThat(InputState.yesNo(keyMap)).isEqualTo("Y/N");
        assertThat(InputState.BOUNDARY_EXPANSION.text(keyMap)).doesNotContain("Y/N"); // shown after the time button
    }

    @Test
    void percentPlaceholdersAreStillFilledByCallers() {
        assertThat(InputState.SEARCH_ACTIVE.text(KeyMap.defaults(), "timeout"))
                .isEqualTo("timeout · F3 next · F4 show matches · Esc end");
        assertThat(InputState.SEARCH_EXTEND.text(KeyMap.defaults(), "later")).isEqualTo("No later matches · search");
        assertThat(InputState.SEARCH_EXTEND_NONE.text(KeyMap.defaults(), "zeta"))
                .isEqualTo("No matches for \"zeta\" · search");
    }

    @Test
    void matchesTextNamesTheShowInLogAndBackKeys() {
        KeyMap keyMap = KeyMap.defaults();
        String rendered = InputState.MATCHES.text(keyMap, "\"timeout\"");

        assertThat(rendered).startsWith("Matches: \"timeout\"");
        assertThat(rendered).contains(keyMap.label(UiAction.SHOW_IN_LOG) + " show in log");
        assertThat(rendered).contains(keyMap.label(UiAction.CANCEL) + " back");
        assertThat(rendered).contains(keyMap.label(UiAction.NEXT_MATCH) + " next match");
    }

    @Test
    void bookmarksTextNamesTheCountAndTheShowRemoveAndBackKeys() {
        KeyMap keyMap = KeyMap.defaults();

        assertThat(InputState.BOOKMARKS.text(keyMap, "3 bookmarks"))
                .isEqualTo("3 bookmarks · Enter show in log · Ctrl-B remove · Esc back");
        assertThat(InputState.BOOKMARKS.text(keyMap.withFlavour(KeyFlavour.PAGER), "1 bookmark"))
                .isEqualTo("1 bookmark · Enter show in log · m remove · Esc back");
        assertThat(InputState.BOOKMARKS.editable).isFalse();
    }

    @Test
    void searchRecentrePromptLeavesTheTimeToTheButton() {
        // The suggested origin is shown on the time button after the prompt, like SEARCH_EXTEND.
        assertThat(InputState.SEARCH_RECENTRE.text(KeyMap.defaults())).isEqualTo("Search around:");
    }

    @Test
    void hintsFollowTheKeyMapFlavour() {
        KeyMap desktop = KeyMap.defaults();
        KeyMap pager = desktop.withFlavour(KeyFlavour.PAGER);

        assertThat(InputState.SEARCH_INPUT.hint(desktop)).startsWith("Enter search · ")
                .contains("F3/Shift-F3 next/prev");
        assertThat(InputState.SEARCH_INPUT.hint(pager)).contains("n/N next/prev");
        assertThat(InputState.SCROLL.text(pager)).contains("n next match").contains("/ to search")
                .contains("v cursor mode");
        assertThat(InputState.SCROLL.hint(desktop)).isEmpty();
    }

    @Test
    void cursorTextsNameTheToggleAndNotEscape() {
        KeyMap keyMap = KeyMap.defaults();

        assertThat(InputState.CURSOR.text(keyMap)).contains("F7 for page mode").doesNotContain("Esc");
        assertThat(InputState.INIT_CURSOR.text(keyMap)).contains("F7 for page mode");
        assertThat(InputState.SELECTING.text(keyMap)).contains("Esc to clear the selection");
    }

    @Test
    void onlyTheTypedInputsAndTheYesNoQuestionsNeedInput() {
        java.util.Set<InputState> needing = java.util.EnumSet.of(InputState.SEARCH_INPUT,
                InputState.BOUNDARY_EXPANSION, InputState.SEARCH_EXTEND,
                InputState.SEARCH_EXTEND_NONE, InputState.SEARCH_RECENTRE, InputState.SCOPE_CONFIRM);
        for (InputState state : InputState.values()) {
            assertThat(state.needsInput()).as(state.name()).isEqualTo(needing.contains(state));
        }
    }
}
