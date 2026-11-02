package se.grynna.pager.splunk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {

    private Preferences node;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("se/grynna/pager/tests/" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    @DisplayName("Matches view defaults to whole events and round-trips the first-line choice")
    void matchesFirstLineOnlyRoundTrip() {
        Config config = new Config(node);
        assertThat(config.getMatchesFirstLineOnly()).isFalse();

        config.setMatchesFirstLineOnly(true);
        assertThat(new Config(node).getMatchesFirstLineOnly()).isTrue();

        config.setMatchesFirstLineOnly(false);
        assertThat(new Config(node).getMatchesFirstLineOnly()).isFalse();
    }

    @Test
    @DisplayName("The search minimap is shown by default and the choice round-trips")
    void showMinimapRoundTrip() {
        Config config = new Config(node);
        assertThat(config.getShowMinimap()).isTrue();

        config.setShowMinimap(false);
        assertThat(new Config(node).getShowMinimap()).isFalse();

        config.setShowMinimap(true);
        assertThat(new Config(node).getShowMinimap()).isTrue();
    }

    @Test
    @DisplayName("Key hints default to the desktop flavour and round-trip")
    void keyHintsRoundTrip() {
        Config config = new Config(node);
        assertThat(config.getKeyHints()).isEqualTo("DESKTOP");

        config.setKeyHints("PAGER");
        assertThat(new Config(node).getKeyHints()).isEqualTo("PAGER");

        config.setKeyHints("EMACS");
        assertThat(new Config(node).getKeyHints()).isEqualTo("EMACS");
    }

    @Test
    @DisplayName("UI layout defaults to most and round-trips win")
    void uiLayoutRoundTrip() {
        Config config = new Config(node);
        assertThat(config.getUiLayout()).isEqualTo(se.grynna.pager.ui.UiLayout.MOST);

        config.setUiLayout(se.grynna.pager.ui.UiLayout.WIN);
        assertThat(new Config(node).getUiLayout()).isEqualTo(se.grynna.pager.ui.UiLayout.WIN);

        config.setUiLayout(se.grynna.pager.ui.UiLayout.MOST);
        assertThat(new Config(node).getUiLayout()).isEqualTo(se.grynna.pager.ui.UiLayout.MOST);
    }
}
