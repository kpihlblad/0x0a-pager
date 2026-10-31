package se.grynna.pager.ui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.ButtonModel;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A compact outlined pill button used by the prompt row and the activity rows. It draws its own stadium capsule so
 * the text and border share the action's accent colour without a filled background at rest, while keeping a rounded
 * hover and press tint.
 */
final class ActionPill extends JButton {

    static final Insets DEFAULT_MARGIN = new Insets(0, 4, 0, 4);
    private static final int HORIZONTAL_PADDING = 4;
    private static final int VERTICAL_PADDING = 5;
    private static final int HOVER_ALPHA = 36;
    private static final int PRESSED_ALPHA = 64;

    private final Color accent;

    ActionPill(String text, Color accent) {
        this(text, accent, Fontalizer.getFont(), DEFAULT_MARGIN);
    }

    ActionPill(String text, Color accent, Font font) {
        this(text, accent, font, DEFAULT_MARGIN);
    }

    ActionPill(String text, Color accent, Font font, Insets margin) {
        super(text);
        this.accent = accent;
        setFont(font);
        setForeground(accent);
        setMargin(margin);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setFocusable(false);
        setOpaque(false);
        setRolloverEnabled(true);
        putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        putClientProperty(FlatClientProperties.MINIMUM_WIDTH, 0);
        putClientProperty(FlatClientProperties.MINIMUM_HEIGHT, 0);
    }

    /** The accent colour of the pill's text and rounded border. */
    Color accent() {
        return accent;
    }

    /** Match the chrome buttons' height while keeping the width the label needs. */
    void alignHeight(int height) {
        Dimension preferred = getPreferredSize();
        setPreferredSize(new Dimension(preferred.width, height));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        FontMetrics fm = getFontMetrics(getFont());
        Insets margin = getMargin();
        int width = fm.stringWidth(getText()) + margin.left + margin.right + HORIZONTAL_PADDING;
        int height = fm.getHeight() + margin.top + margin.bottom + VERTICAL_PADDING;
        return new Dimension(width, height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ButtonModel model = getModel();
            if (isEnabled() && (model.isRollover() || model.isPressed())) {
                int alpha = model.isPressed() ? PRESSED_ALPHA : HOVER_ALPHA;
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha));
                g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, h - 1f, h - 1f));
            }
            g2.setColor(accent);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, h - 1f, h - 1f));

            Insets margin = getMargin();
            FontMetrics fm = g2.getFontMetrics(getFont());
            int innerW = w - margin.left - margin.right;
            int innerH = h - margin.top - margin.bottom;
            int x = margin.left + (innerW - fm.stringWidth(getText())) / 2;
            int y = margin.top + (innerH - fm.getHeight()) / 2 + fm.getAscent();
            g2.setFont(getFont());
            g2.setColor(getForeground());
            g2.drawString(getText(), x, y);
        } finally {
            g2.dispose();
        }
    }
}
