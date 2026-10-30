package se.grynna.pager.ui.login;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.Fontalizer;

import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class KeyButtonTest {

    @Test
    void textIsTheActionAndTheKeyIsSeparate() {
        KeyButton button = new KeyButton("Ctrl+Enter", "apply", Fontalizer.getFont(), true);

        assertThat(button.getText()).isEqualTo("apply");
        assertThat(button.getKey()).isEqualTo("Ctrl+Enter");
        assertThat(button.isPrimary()).isTrue();
    }

    @Test
    void preferredWidthFitsKeycapAndAction() {
        Font font = Fontalizer.getFont();
        KeyButton shortKey = new KeyButton("Esc", "cancel", font, false);
        KeyButton longKey = new KeyButton("Ctrl+Esc", "cancel", font, false);

        FontMetrics fm = shortKey.getFontMetrics(font);
        assertThat(shortKey.getPreferredSize().width).isGreaterThan(fm.stringWidth("Esc cancel"));
        assertThat(longKey.getPreferredSize().width).isGreaterThan(shortKey.getPreferredSize().width);
    }

    @Test
    void clickRunsTheActionUnlessDisabled() {
        KeyButton button = new KeyButton("Esc", "cancel", Fontalizer.getFont(), false);
        AtomicInteger clicks = new AtomicInteger();
        button.addActionListener(e -> clicks.incrementAndGet());

        button.doClick();
        button.setEnabled(false);
        button.doClick();

        assertThat(clicks).hasValue(1);
    }
}
