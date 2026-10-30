package se.grynna.pager.ui.login;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.Fontalizer;

import javax.swing.*;

import static org.assertj.core.api.Assertions.assertThat;

class KeyHintBarTest {

    @Test
    void buildsOneChildPerHintWithKeyAndActionLabels() {
        KeyHintBar bar = new KeyHintBar(
                Fontalizer.getFont(), new KeyHintBar.Hint("Enter", "open"), new KeyHintBar.Hint("Esc", "close"));

        assertThat(bar.getComponentCount()).isEqualTo(2);

        JPanel first = (JPanel) bar.getComponent(0);
        assertThat(((JLabel) first.getComponent(0)).getText()).isEqualTo("Enter");
        assertThat(((JLabel) first.getComponent(1)).getText()).isEqualTo("open");

        JPanel second = (JPanel) bar.getComponent(1);
        assertThat(((JLabel) second.getComponent(0)).getText()).isEqualTo("Esc");
        assertThat(((JLabel) second.getComponent(1)).getText()).isEqualTo("close");
    }
}
