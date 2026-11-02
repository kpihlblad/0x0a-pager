package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

class BlockCaretTest {

    @Test
    void damageWidensCaretToOneCharacter() {
        JTextField field = new JTextField("abc");
        BlockCaret caret = new BlockCaret(() -> DarculaColors.ORANGE);
        field.setCaret(caret);

        caret.damage(new Rectangle(10, 5, 1, 16));

        assertThat(caret.width).isGreaterThan(1);
    }
}
