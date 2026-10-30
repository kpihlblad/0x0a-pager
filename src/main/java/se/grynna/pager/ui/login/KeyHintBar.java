package se.grynna.pager.ui.login;

import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * A row of CLI-style keyboard hints, each a keycap followed by its action, for example {@code [Enter] open}.
 */
public class KeyHintBar extends JPanel {

    public record Hint(String key, String action) {}

    static final float HINT_FONT_SIZE = 11f;
    static final int KEYCAP_ARC = 6;

    /**
     * @param font  the font of the keycaps and actions (derived to 11 pt)
     * @param hints the hints, in display order
     */
    public KeyHintBar(Font font, Hint... hints) {
        Font hintFont = font.deriveFont(HINT_FONT_SIZE);
        setLayout(new FlowLayout(FlowLayout.LEFT, 14, 0));
        setOpaque(false);

        for (Hint hint : hints) {
            JPanel hintPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            hintPanel.setOpaque(false);

            JLabel keycap = new KeycapLabel(hint.key());
            keycap.setFont(hintFont);
            keycap.setForeground(DarculaColors.G8);
            keycap.setBorder(new EmptyBorder(1, 6, 1, 6));

            JLabel action = new JLabel(hint.action());
            action.setFont(hintFont);
            action.setForeground(DarculaColors.G7);

            hintPanel.add(keycap);
            hintPanel.add(action);
            add(hintPanel);
        }
    }

    private static class KeycapLabel extends JLabel {

        KeycapLabel(String text) {
            super(text);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(DarculaColors.G3);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), KEYCAP_ARC, KEYCAP_ARC));
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
