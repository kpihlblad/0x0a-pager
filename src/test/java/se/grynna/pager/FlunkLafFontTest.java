package se.grynna.pager;

import com.formdev.flatlaf.FlatClientProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.Fontalizer;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

class PagerLafFontTest {

    @BeforeAll
    static void installLaf() {
        PagerLaf.setup();
    }

    @Test
    void uiFontIsIbmPlexSansWithRealFaces() {
        // FlatLaf scales the absolute defaultFont size (15) by the system font scale, so only compare sizes here.
        Font regular = Fontalizer.getUiFont();
        assertThat(regular.getFontName()).isEqualTo("IBM Plex Sans Regular");
        assertThat(regular.deriveFont(Font.BOLD).getFontName()).isEqualTo("IBM Plex Sans Bold");
        assertThat(Fontalizer.getUiSemiboldFont().getFontName()).isEqualTo("IBM Plex Sans SemiBold");
        assertThat(Fontalizer.getUiSemiboldFont().getSize()).isEqualTo(regular.getSize());
    }

    @Test
    void chromeIsRegularAndTheSemiboldStyleClassOptsIn() {
        for (String key : new String[]{"Label.font", "Button.font", "Menu.font", "MenuItem.font", "PopupMenu.font",
                "ToolTip.font", "ComboBox.font", "List.font", "OptionPane.font", "TextField.font"}) {
            assertThat(UIManager.getFont(key).getFamily()).as(key).isEqualTo(Fontalizer.UI_FAMILY);
        }
        JLabel label = new JLabel("Host");
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
        assertThat(label.getFont().getFontName()).isEqualTo("IBM Plex Sans SemiBold");
        assertThat(label.getFont().getSize()).isEqualTo(Fontalizer.getUiFont().getSize());
    }

    @Test
    void theLogFontStaysMonospace() {
        assertThat(Fontalizer.getFont().getFamily()).doesNotContain("Plex");
    }
}
