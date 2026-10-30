package se.grynna.pager.ui.login;

import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * A footer button drawn like a {@link KeyHintBar} hint inside a rounded box: the keycap of its keyboard
 * shortcut followed by the action, e.g. {@code [Esc] cancel}. The default button of a form has an amber
 * outline, an amber tint and amber action text. The button text is the action; the key is fixed.
 */
public class KeyButton extends JButton {

    private static final int PAD_X = 8;
    private static final int PAD_Y = 4;
    private static final int GAP = 8;
    private static final int KEYCAP_PAD_X = 6;
    private static final int KEYCAP_PAD_Y = 1;
    private static final int BOX_ARC = 10;

    private final String key;
    private final boolean primary;
    private final Font keyFont;

    /**
     * @param key     the keycap text, e.g. {@code "Ctrl+Enter"}
     * @param action  the action, the button's text
     * @param font    the monospace base font; the keycap uses the hint size, a primary action is bold
     * @param primary whether this is the form's default button
     */
    public KeyButton(String key, String action, Font font, boolean primary) {
        super(action);
        this.key = key;
        this.primary = primary;
        this.keyFont = font.deriveFont(KeyHintBar.HINT_FONT_SIZE);
        setFont(primary ? font.deriveFont(Font.BOLD) : font);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
    }

    public String getKey() {
        return key;
    }

    public boolean isPrimary() {
        return primary;
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        FontMetrics km = getFontMetrics(keyFont);
        FontMetrics am = getFontMetrics(getFont());
        int keycapW = km.stringWidth(key) + 2 * KEYCAP_PAD_X;
        int keycapH = km.getHeight() + 2 * KEYCAP_PAD_Y;
        int width = PAD_X + keycapW + GAP + am.stringWidth(getText()) + PAD_X;
        int height = Math.max(keycapH, am.getHeight()) + 2 * PAD_Y;
        return new Dimension(width, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean enabled = isEnabled();
            ButtonModel model = getModel();
            boolean hot = enabled && (model.isRollover() || model.isPressed());

            Color fill = boxFill(enabled, hot);
            if (fill != null) {
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), BOX_ARC, BOX_ARC));
            }
            g2.setColor(outline(enabled));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, BOX_ARC, BOX_ARC));

            FontMetrics km = g2.getFontMetrics(keyFont);
            FontMetrics am = g2.getFontMetrics(getFont());
            int keycapW = km.stringWidth(key) + 2 * KEYCAP_PAD_X;
            int keycapH = km.getHeight() + 2 * KEYCAP_PAD_Y;
            int keycapY = (getHeight() - keycapH) / 2;
            g2.setColor(enabled ? DarculaColors.G3 : DarculaColors.G2);
            g2.fill(new RoundRectangle2D.Float(PAD_X, keycapY, keycapW, keycapH, KeyHintBar.KEYCAP_ARC,
                    KeyHintBar.KEYCAP_ARC));
            g2.setFont(keyFont);
            g2.setColor(enabled ? DarculaColors.G8 : DarculaColors.G6);
            g2.drawString(key, PAD_X + KEYCAP_PAD_X, keycapY + KEYCAP_PAD_Y + km.getAscent());

            g2.setFont(getFont());
            g2.setColor(actionColor(enabled, hot));
            int actionY = (getHeight() - am.getHeight()) / 2 + am.getAscent();
            g2.drawString(getText(), PAD_X + keycapW + GAP, actionY);
        } finally {
            g2.dispose();
        }
    }

    private Color boxFill(boolean enabled, boolean hot) {
        if (primary && enabled) {
            return hot ? DarculaColors.KEY_BUTTON_DEFAULT_HOVER : DarculaColors.KEY_BUTTON_DEFAULT;
        }
        return hot ? DarculaColors.G2 : null;
    }

    private Color outline(boolean enabled) {
        if (!enabled) {
            return DarculaColors.G3;
        }
        if (isFocusOwner()) {
            return primary ? DarculaColors.YELLOW : DarculaColors.G7;
        }
        return primary ? DarculaColors.ORANGE : DarculaColors.G5;
    }

    private Color actionColor(boolean enabled, boolean hot) {
        if (!enabled) {
            return DarculaColors.G6;
        }
        if (primary) {
            return DarculaColors.ORANGE;
        }
        return hot ? DarculaColors.G9 : DarculaColors.G8;
    }
}
