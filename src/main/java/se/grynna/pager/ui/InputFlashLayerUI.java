package se.grynna.pager.ui;

import javax.swing.*;
import javax.swing.plaf.LayerUI;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Supplier;

/**
 * The overlay of the input bar's attention flash, painted after the status bar's two
 * rows while the {@link AttentionFlash} runs: a pop of light over the input row and a glare with a thin outline
 * around the answer zone. As a {@link JLayer} is a painting origin, a child repainting on its own (the caret
 * blinking) paints the overlay too. The glare bleeds upwards over the status row.
 */
final class InputFlashLayerUI extends LayerUI<JComponent> {

    /** The minimap's blue-white glare. */
    static final Color GLOW = new Color(200, 228, 255);
    static final float TINT_ALPHA = 0.28f;
    static final int GLARE_REACH = 10;
    static final float GLARE_PEAK = 0.55f;
    static final int CENTER_PAD = 6;

    private final AttentionFlash flash;
    private final JComponent inputRow;
    private final Supplier<List<Component>> answerZone;
    private final AttentionFlash centerFlash;
    private final JComponent centerLabel;

    private record GlareKey(int width, int height) {}

    /** One rendered glare, kept while the size it is asked for stays the same. */
    private static final class GlareCache {
        private GlareKey cachedKey;
        private BufferedImage cachedGlare;

        BufferedImage get(int width, int height) {
            GlareKey key = new GlareKey(width, height);
            if (!key.equals(cachedKey)) {
                cachedGlare = Glow.render(width, height, GLARE_REACH, GLOW, GLARE_PEAK);
                cachedKey = key;
            }
            return cachedGlare;
        }
    }

    private final GlareCache inputGlare = new GlareCache();
    private final GlareCache centerGlare = new GlareCache();

    /**
     * @param flash      the timeline; the overlay shows while its intensity is above 0
     * @param inputRow   the input row, tinted as a whole
     * @param answerZone the components the answer goes in (the visible ones); empty or not laid out means the
     *                   whole input row
     */
    InputFlashLayerUI(AttentionFlash flash, JComponent inputRow, Supplier<List<Component>> answerZone) {
        this(flash, inputRow, answerZone, null, null);
    }

    /**
     * As above, plus the blue-bar centre's glare: a pop of light and a glare around the
     * centre label's text while {@code centerFlash} runs.
     */
    InputFlashLayerUI(AttentionFlash flash, JComponent inputRow, Supplier<List<Component>> answerZone,
            AttentionFlash centerFlash, JComponent centerLabel) {
        this.flash = flash;
        this.inputRow = inputRow;
        this.answerZone = answerZone;
        this.centerFlash = centerFlash;
        this.centerLabel = centerLabel;
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        super.paint(g, c);
        if (!(c instanceof JLayer<?> layer)) {
            return;
        }
        paintInput(g, layer);
        paintCenter(g, layer);
    }

    private void paintCenter(Graphics g, JLayer<?> layer) {
        if (centerFlash == null || centerLabel == null) {
            return;
        }
        float intensity = centerFlash.intensity();
        if (intensity <= 0f) {
            return;
        }
        Rectangle text = centerTextBounds(layer);
        if (text.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(withAlpha(GLOW, TINT_ALPHA * intensity));
            g2.fillRect(text.x, text.y, text.width, text.height);
            BufferedImage glare = centerGlare.get(text.width, text.height);
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, intensity)));
            g2.drawImage(glare, text.x - GLARE_REACH, text.y - GLARE_REACH, null);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setColor(withAlpha(GLOW, intensity));
            g2.drawRect(text.x, text.y, text.width - 1, text.height - 1);
        } finally {
            g2.dispose();
        }
    }

    /** The centre text's box in layer coordinates: the text plus a little padding, centred in the label. */
    Rectangle centerTextBounds(JLayer<?> layer) {
        if (centerLabel instanceof TimeGroup group) {
            if (!group.hasRange()) return new Rectangle();
            return SwingUtilities.convertRectangle(group, group.halfBounds(TimeGroup.Half.RANGE), layer);
        }
        if (!(centerLabel instanceof JLabel textLabel) || !centerLabel.isVisible() || textLabel.getText().isEmpty()) {
            return new Rectangle();
        }
        Rectangle label = inLayer(centerLabel, layer);
        if (label.isEmpty()) {
            return label;
        }
        int textWidth = centerLabel.getFontMetrics(centerLabel.getFont()).stringWidth(textLabel.getText());
        int width = Math.min(label.width, textWidth + 2 * CENTER_PAD);
        return new Rectangle(label.x + (label.width - width) / 2, label.y, width, label.height);
    }

    private void paintInput(Graphics g, JLayer<?> layer) {
        float intensity = flash.intensity();
        if (intensity <= 0f) {
            return;
        }
        Rectangle row = inLayer(inputRow, layer);
        if (row.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(withAlpha(GLOW, TINT_ALPHA * intensity));
            g2.fillRect(row.x, row.y, row.width, row.height);

            Rectangle zone = zone(layer, row);
            BufferedImage glare = inputGlare.get(zone.width, zone.height);
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, intensity)));
            g2.drawImage(glare, zone.x - GLARE_REACH, zone.y - GLARE_REACH, null);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setColor(withAlpha(GLOW, intensity));
            g2.drawRect(zone.x, zone.y, zone.width - 1, zone.height - 1);
        } finally {
            g2.dispose();
        }
    }

    /** The answer zone in layer coordinates: the union of the laid-out answer components, else the input row. */
    Rectangle zone(JLayer<?> layer, Rectangle row) {
        Rectangle union = null;
        for (Component component : answerZone.get()) {
            Rectangle bounds = inLayer(component, layer);
            if (bounds.isEmpty()) {
                continue;
            }
            union = union == null ? bounds : union.union(bounds);
        }
        return union == null ? row : union;
    }

    private static Rectangle inLayer(Component component, JLayer<?> layer) {
        Container parent = component.getParent();
        if (parent == null) {
            return new Rectangle();
        }
        return SwingUtilities.convertRectangle(parent, component.getBounds(), layer);
    }

    private static Color withAlpha(Color color, float alpha) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }
}
