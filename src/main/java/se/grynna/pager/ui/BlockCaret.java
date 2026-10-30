package se.grynna.pager.ui;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.geom.Rectangle2D;

/**
 * A caret drawn as a translucent block one character wide, like an old terminal cursor.
 */
public class BlockCaret extends DefaultCaret {

    private static final int BLOCK_ALPHA = 150;
    private static final int BLINK_RATE_MS = 500;

    private final Color color;

    public BlockCaret(Color color) {
        this.color = color;
        setBlinkRate(BLINK_RATE_MS);
    }

    @Override
    public void paint(Graphics g) {
        if (!isVisible()) {
            return;
        }
        JTextComponent component = getComponent();
        try {
            Rectangle2D view = component.modelToView2D(getDot());
            int blockWidth = g.getFontMetrics(component.getFont()).charWidth('M');
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), BLOCK_ALPHA));
            g.fillRect((int) view.getX(), (int) view.getY(), blockWidth, (int) view.getHeight());
        } catch (BadLocationException e) {
            // the dot is outside the document; nothing to paint
        }
    }

    @Override
    protected synchronized void damage(Rectangle r) {
        if (r != null) {
            JTextComponent component = getComponent();
            x = r.x;
            y = r.y;
            height = r.height;
            width = component.getFontMetrics(component.getFont()).charWidth('M');
            component.repaint(x, y, width, height);
        }
    }
}
