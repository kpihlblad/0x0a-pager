package se.grynna.pager.ui;

import com.formdev.flatlaf.FlatLaf;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.Theme;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.UIResource;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.Map;

/**
 * Switches the running application between the dark and the light theme. The palette is replaced first; then every
 * colour that a component holds explicitly and that belongs to the old palette is replaced by its counterpart, and
 * finally the look and feel is reinstalled. Colours that FlatLaf set itself are reinstalled by that step, and paint
 * code reads the palette when it draws, so neither needs a remap. Call on the EDT.
 */
final class ThemeSwitch {

    private ThemeSwitch() {}

    static void apply(Theme theme) {
        Map<Color, Color> remap = DarculaColors.useAndRemap(theme);
        for (Window window : Window.getWindows()) {
            recolour(window, remap);
        }
        PagerLaf.install(theme);
        FlatLaf.updateUI();
    }

    static void recolour(Component component, Map<Color, Color> remap) {
        Color background = component.getBackground();
        Color newBackground = mapped(background, remap);
        if (newBackground != background) {
            component.setBackground(newBackground);
        }
        Color foreground = component.getForeground();
        Color newForeground = mapped(foreground, remap);
        if (newForeground != foreground) {
            component.setForeground(newForeground);
        }
        if (component instanceof JComponent jc) {
            Border border = jc.getBorder();
            Border newBorder = recolourBorder(border, remap);
            if (newBorder != border) {
                jc.setBorder(newBorder);
            }
            if (jc instanceof JTextComponent text) {
                text.setCaretColor(mapped(text.getCaretColor(), remap));
                text.setSelectionColor(mapped(text.getSelectionColor(), remap));
                text.setSelectedTextColor(mapped(text.getSelectedTextColor(), remap));
            }
            JPopupMenu popup = jc.getComponentPopupMenu();
            if (popup != null) {
                recolour(popup, remap);
            }
        }
        if (component instanceof JMenu menu) {
            recolour(menu.getPopupMenu(), remap);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                recolour(child, remap);
            }
        }
    }

    /** The replacement of {@code colour}, or {@code colour} itself when it is FlatLaf's or not part of the old palette. */
    private static Color mapped(Color colour, Map<Color, Color> remap) {
        if (colour == null || colour instanceof UIResource) {
            return colour;
        }
        return remap.getOrDefault(colour, colour);
    }

    private static Border recolourBorder(Border border, Map<Color, Color> remap) {
        if (border instanceof CompoundBorder compound) {
            return new CompoundBorder(recolourBorder(compound.getOutsideBorder(), remap),
                    recolourBorder(compound.getInsideBorder(), remap));
        }
        if (border instanceof LineBorder line) {
            Color colour = mapped(line.getLineColor(), remap);
            return colour == line.getLineColor() ? border
                    : new LineBorder(colour, line.getThickness(), line.getRoundedCorners());
        }
        if (border instanceof MatteBorder matte && matte.getMatteColor() != null) {
            Color colour = mapped(matte.getMatteColor(), remap);
            return colour == matte.getMatteColor() ? border : new MatteBorder(matte.getBorderInsets(), colour);
        }
        return border;
    }
}
