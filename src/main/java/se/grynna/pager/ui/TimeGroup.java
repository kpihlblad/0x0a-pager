package se.grynna.pager.ui;

import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/** Painted, non-focusable position and range control in the status row. EDT only. */
public final class TimeGroup extends JComponent {

    public enum Half {
        AT, RANGE
    }

    private static final int PAD = 8;
    /**
     * Longest range text the half reserves width for. A plain range is at most two full datetimes (41 chars); an
     * edge note combined with a range is longer, and then the half sizes itself by a shorter fallback so the note
     * cannot stretch the status row.
     */
    private static final int MAX_RANGE_CHARS = 42;
    private final Font wordFont;
    private final int rowHeight;
    private String dateWord = "AT";
    private String dateText = "";
    private String rangeWord = "RANGE";
    private String rangeText = "";
    private String[] fallbacks = {};
    private String rangeTooltip;
    private Half hovered;
    private Consumer<Half> onClick = half -> {};

    public TimeGroup(Font font) {
        setFont(font);
        wordFont = font.deriveFont(Font.BOLD, Math.max(9f, font.getSize() - 2f));
        rowHeight = getFontMetrics(font).getHeight() + 4;
        setForeground(DarculaColors.FOREGROUND);
        setFocusable(false);
        setOpaque(false);
        setEnabled(false);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Half half = halfAt(e.getPoint());
                if (half != null && SwingUtilities.isLeftMouseButton(e)) onClick.accept(half);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hover(halfAt(e.getPoint()));
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover(halfAt(e.getPoint()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover(null);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void setOnClick(Consumer<Half> listener) {
        onClick = listener == null ? half -> {} : listener;
    }

    void setDate(String word, String text) {
        dateWord = word;
        dateText = text;
        changed();
    }

    void setRange(String word, String text, Color color, String tooltip, String... fallbacks) {
        rangeWord = word;
        rangeText = text;
        rangeTooltip = tooltip;
        this.fallbacks = fallbacks.clone();
        setForeground(color);
        changed();
    }

    String dateWord() {
        return dateWord;
    }

    String dateText() {
        return dateText;
    }

    String rangeWord() {
        return rangeWord;
    }

    String rangeTooltip() {
        return rangeTooltip;
    }

    boolean hasRange() {
        return !rangeText.isEmpty();
    }

    String rangeText() {
        int available = halfBounds(Half.RANGE).width - wordWidth(rangeWord) - 2 * PAD;
        if (getWidth() <= 0 || getFontMetrics(getFont()).stringWidth(rangeText) <= available) return rangeText;
        String text = rangeText;
        for (String candidate : fallbacks) {
            text = candidate;
            if (getFontMetrics(getFont()).stringWidth(candidate) <= available) break;
        }
        return text;
    }

    Rectangle halfBounds(Half half) {
        int split = Math.min(getWidth(), dateWidth());
        return half == Half.AT
                ? new Rectangle(0, 0, split, getHeight())
                : new Rectangle(split, 0, Math.max(0, getWidth() - split), getHeight());
    }

    private Half halfAt(Point point) {
        if (!isEnabled()) return null;
        for (Half half : Half.values())
            if (halfBounds(half).contains(point)) return half;
        return null;
    }

    private void hover(Half half) {
        hovered = half;
        setCursor(java.awt.Cursor.getPredefinedCursor(
                half == null ? java.awt.Cursor.DEFAULT_CURSOR : java.awt.Cursor.HAND_CURSOR));
        repaint();
    }

    private void changed() {
        setEnabled(!dateText.isEmpty() || !rangeText.isEmpty());
        if (!isEnabled()) hover(null);
        revalidate();
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        Half half = halfAt(event.getPoint());
        if (half == null) return null;
        if (half == Half.AT) return "Go to a time (" + KeyMap.preferred().label(UiAction.GOTO) + ")";
        return "Set the time range (" + KeyMap.preferred().label(UiAction.SET_RANGE) + ")"
                + (rangeTooltip == null ? "" : "\n" + rangeTooltip);
    }

    @Override
    public Point getToolTipLocation(MouseEvent event) {
        return InWindowToolTip.locationFor(this, event);
    }

    private int wordWidth(String word) {
        return getFontMetrics(wordFont).stringWidth(word + " ");
    }

    private int dateWidth() {
        return wordWidth(dateWord) + getFontMetrics(getFont()).stringWidth(dateText) + 2 * PAD + 1;
    }

    /** The text the half reserves width for: the full range, or the first fallback that fits {@link #MAX_RANGE_CHARS}. */
    private String sizedRangeText() {
        if (rangeText.length() <= MAX_RANGE_CHARS) return rangeText;
        for (String candidate : fallbacks) {
            if (candidate.length() <= MAX_RANGE_CHARS) return candidate;
        }
        return fallbacks.length > 0 ? fallbacks[fallbacks.length - 1] : rangeText;
    }

    @Override
    public Dimension getPreferredSize() {
        if (!isEnabled()) return new Dimension(0, rowHeight);
        return new Dimension(dateWidth() + wordWidth(rangeWord)
                + getFontMetrics(getFont()).stringWidth(sizedRangeText()) + 2 * PAD, rowHeight);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(isEnabled() ? dateWidth() + wordWidth(rangeWord) + 2 * PAD + 40 : 0, rowHeight);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(getPreferredSize().width, rowHeight);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (!isEnabled()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Shape outline = new java.awt.geom.RoundRectangle2D.Float(.5f, .5f,
                    Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 6, 6);
            Shape clip = g.getClip();
            g.clip(outline);
            for (Half half : Half.values()) {
                Rectangle bounds = halfBounds(half);
                if (half == hovered) {
                    g.setColor(DarculaColors.BLUE);
                    g.fill(bounds);
                }
                Graphics2D text = (Graphics2D) g.create();
                try {
                    text.clip(bounds);
                    String word = half == Half.AT ? dateWord : rangeWord;
                    String value = half == Half.AT ? dateText : rangeText();
                    int x = bounds.x + PAD;
                    FontMetrics metrics = text.getFontMetrics(getFont());
                    int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                    text.setFont(wordFont);
                    text.setColor(half == hovered ? DarculaColors.FOREGROUND : DarculaColors.COMMENT);
                    text.drawString(word, x, baseline);
                    text.setFont(getFont());
                    text.setColor(half == Half.RANGE ? getForeground() : DarculaColors.FOREGROUND);
                    if (half == hovered) text.setColor(text.getColor().brighter());
                    text.drawString(value, x + wordWidth(word), baseline);
                } finally {
                    text.dispose();
                }
            }
            g.setColor(DarculaColors.G5);
            int split = halfBounds(Half.RANGE).x;
            g.drawLine(split, 3, split, getHeight() - 4);
            g.setClip(clip);
            g.draw(outline);
        } finally {
            g.dispose();
        }
    }
}
