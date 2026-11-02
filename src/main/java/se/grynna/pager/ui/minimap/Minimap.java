package se.grynna.pager.ui.minimap;

import se.grynna.pager.ui.Glow;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.stream.LongStream;

/**
 * The minimap: a thin strip at the right edge of the log, always there, showing which part the viewport
 * shows, the day, week, month or year bands, where the bookmarks are, and, while a search is active, where its
 * matches are. Painted, and clickable: a click centres the viewport on that place and a drag moves the
 * visible range (see {@link MinimapDrag}), through the {@link MinimapNavigator}; a target within
 * {@link MinimapDrag#SNAP_PX} px of a match snaps onto it. Only the strip and
 * {@link #HIT_MARGIN} px to its left take the mouse ({@link #contains(int, int)}), and only while there is
 * something drawn; text selection, clicks and the wheel everywhere else reach the log underneath, as with the
 * floating scroll bar. The wheel over the hit area is forwarded to the log by the owner (a wheel listener).
 * The minimap never takes keyboard focus.
 * <p>
 * The component is {@link #GLOW_REACH} px wider than the strip: the strip is painted at x in
 * {@code [GLOW_REACH, getWidth())} and the gutter on its left holds only the glow of the visible range, which
 * fades out over {@code GLOW_REACH} px into the log text.
 * <p>
 * EDT-confined. The data is set by {@link MinimapController}; a {@link MinimapModel} is built from it at
 * paint time and kept until the data, the visible range or the height changes.
 */
public final class Minimap extends JComponent {

    /** Slice height in pixels. */
    static final int SLICE_HEIGHT = 3;
    /** Width of the glow gutter on the left of the component; the glow fades out over this distance. */
    public static final int GLOW_REACH = 14;
    /** How far left of the strip the hit area reaches: the strip is thin. */
    public static final int HIT_MARGIN = 3;

    private static final float GLOW_PEAK = 0.55f;
    private static final float GLOW_INNER = 0.18f;

    /** What the strip shows; replaced as a whole. */
    record Data(MinimapModel.Span span, long[] matches, long[] bookmarks, long[] dateChanges,
            OptionalLong firstEpochDay, boolean capEarlier, boolean capLater) {}

    /** The glow is cached per visible-range height, strip width and theme. */
    private record GlowKey(int rectHeight, int stripWidth, Theme theme) {}

    private Data data;
    private MinimapModel.Span visible;
    private MinimapModel model;
    private int modelHeight;
    private BufferedImage glow;
    private GlowKey glowKey;
    private int glowRenders;
    private MinimapNavigator navigator;
    private final MinimapDrag drag = new MinimapDrag();

    public Minimap() {
        setOpaque(false);
        setFocusable(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter pointer = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    pressed(e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (drag.isActive()) {
                    dragged(e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (drag.isActive()) {
                    drag.release();
                    e.consume();
                }
            }
        };
        addMouseListener(pointer);
        addMouseMotionListener(pointer);
    }

    /** Who scrolls the log on a click or drag; null (the default) leaves the minimap without mouse handling. */
    public void setNavigator(MinimapNavigator navigator) {
        this.navigator = navigator;
    }

    /**
     * Only the strip and {@link #HIT_MARGIN} px left of it take the mouse, and only while there is data to show
     * and a navigator to tell; everything else passes through to the log.
     */
    @Override
    public boolean contains(int x, int y) {
        return data != null && navigator != null && isVisible() && x >= hitLeft() && x < getWidth() && y >= 0
                && y < getHeight();
    }

    /** First x of the hit area: the strip's left edge less {@link #HIT_MARGIN}, never left of the component. */
    private int hitLeft() {
        int gutter = getWidth() > GLOW_REACH ? GLOW_REACH : 0;
        return Math.max(0, gutter - HIT_MARGIN);
    }

    private void pressed(int y) {
        if (data == null || navigator == null || getHeight() < 1) {
            return;
        }
        drag.press(data.span(), getHeight(), visible, y).ifPresent(navigator::centerOn);
    }

    private void dragged(int y) {
        if (data == null || navigator == null || getHeight() < 1) {
            return;
        }
        drag.drag(data.span(), getHeight(), y).ifPresent(navigator::centerOn);
    }

    /**
     * Show a strip for {@code span} (axis units as in {@link MinimapModel}). The matches and the bookmarks are also
     * the snap targets of clicks and drags.
     *
     * @param matches        match positions (unsorted, duplicates allowed); the clicks snap onto them
     * @param dateChanges    axis positions of the local midnights in the span, ascending
     * @param firstEpochDay  the local epoch day of the span's first instant, for the day bands (see
     *                       {@link MinimapModel#build}); empty for no bands
     * @param capEarlier     mark the top edge as truncated (dim dashed cap)
     * @param capLater       mark the bottom edge as truncated
     */
    public void setData(MinimapModel.Span span, long[] matches, long[] bookmarks, long[] dateChanges,
            OptionalLong firstEpochDay, boolean capEarlier, boolean capLater) {
        data = new Data(span, matches, bookmarks, dateChanges, firstEpochDay, capEarlier, capLater);
        drag.setMatches(snapTargets(matches, bookmarks));
        model = null;
        repaint();
    }

    private static long[] snapTargets(long[] matches, long[] bookmarks) {
        return LongStream.concat(matches == null ? LongStream.empty() : Arrays.stream(matches),
                bookmarks == null ? LongStream.empty() : Arrays.stream(bookmarks)).toArray();
    }

    /** The part of the axis the viewport shows; null for none (no outline). */
    public void setVisibleRange(MinimapModel.Span visibleRange) {
        if (java.util.Objects.equals(visible, visibleRange)) {
            return;
        }
        visible = visibleRange;
        model = null;
        repaint();
    }

    /** Draw nothing until new data arrives. */
    public void clear() {
        if (data == null && visible == null) {
            return;
        }
        data = null;
        visible = null;
        model = null;
        repaint();
    }

    /** True when there is something to draw. */
    public boolean hasData() {
        return data != null;
    }

    /** How many times the glow image was rendered (it is reused while its key is unchanged). */
    int glowRenders() {
        return glowRenders;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Data shown = data;
        int w = getWidth();
        int h = getHeight();
        if (shown == null || w < 1 || h < 1) {
            return;
        }
        if (model == null || modelHeight != h) {
            model = MinimapModel.build(shown.span(), h, SLICE_HEIGHT, shown.matches(), shown.bookmarks(),
                    shown.dateChanges(), shown.firstEpochDay(), visible);
            modelHeight = h;
        }
        int gutter = w > GLOW_REACH ? GLOW_REACH : 0;
        int stripWidth = w - gutter;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.translate(gutter, 0);
            paintDays(g2, model, stripWidth);
            paintSlices(g2, model, stripWidth, h);
            if (shown.capEarlier()) {
                paintCap(g2, 0, stripWidth);
            }
            if (shown.capLater()) {
                paintCap(g2, h - 1, stripWidth);
            }
            g2.setColor(DarculaColors.BOOKMARK);
            for (int y : model.bookmarkYs()) {
                g2.fillRect(0, y, stripWidth, 1);
            }
            if (visible != null) {
                paintVisible(g2, model, stripWidth);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Glow outside the range (into the gutter and over the strip above and below), then fill and outline. */
    private void paintVisible(Graphics2D g2, MinimapModel m, int stripWidth) {
        int y0 = m.visibleFromY();
        int y1 = m.visibleToY();
        int rectHeight = y1 - y0;
        g2.drawImage(glow(rectHeight, stripWidth), -GLOW_REACH, y0 - GLOW_REACH, null);
        g2.setColor(alpha(DarculaColors.GLOW, GLOW_INNER));
        g2.fillRect(0, y0, stripWidth, rectHeight);
        g2.setColor(DarculaColors.GLOW);
        g2.fillRect(0, y0, stripWidth, 1);
        g2.fillRect(0, y1 - 1, stripWidth, 1);
        g2.fillRect(0, y0, 1, rectHeight);
        g2.fillRect(stripWidth - 1, y0, 1, rectHeight);
    }

    private BufferedImage glow(int rectHeight, int stripWidth) {
        GlowKey key = new GlowKey(rectHeight, stripWidth, DarculaColors.theme());
        if (!key.equals(glowKey)) {
            glow = renderGlow(rectHeight, stripWidth);
            glowKey = key;
            glowRenders++;
        }
        return glow;
    }

    /**
     * The glow around a rectangle of {@code rectHeight} rows and {@code stripWidth} columns (see
     * {@link Glow#render}), cropped to the left, top and bottom: the strip has nothing to its right.
     */
    static BufferedImage renderGlow(int rectHeight, int stripWidth) {
        BufferedImage full = Glow.render(stripWidth, rectHeight, GLOW_REACH, DarculaColors.GLOW, GLOW_PEAK);
        return full.getSubimage(0, 0, GLOW_REACH + stripWidth, rectHeight + 2 * GLOW_REACH);
    }

    private static Color alpha(Color color, float opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(255 * opacity));
    }

    /** The shaded day, week, month or year bands, solid across the full width. */
    private static void paintDays(Graphics2D g2, MinimapModel m, int w) {
        g2.setColor(DarculaColors.GREY_BAND);
        for (MinimapModel.Band band : m.shadedBands()) {
            g2.fillRect(0, band.top(), w, band.bottom() - band.top());
        }
    }

    private static void paintSlices(Graphics2D g2, MinimapModel m, int w, int h) {
        float[] alphas = m.alphas();
        int count = alphas.length;
        for (int i = 0; i < count; i++) {
            if (alphas[i] <= 0f) {
                continue;
            }
            int y0 = (int) ((long) i * h / count);
            int y1 = (int) ((long) (i + 1) * h / count);
            Color match = DarculaColors.MATCHES_ACCENT;
            g2.setColor(new Color(match.getRed(), match.getGreen(), match.getBlue(), Math.round(255 * alphas[i])));
            g2.fillRect(0, y0, w, Math.max(1, y1 - y0));
        }
    }

    private static void paintCap(Graphics2D g2, int y, int w) {
        g2.setColor(DarculaColors.MATCHES_ACCENT_DIM);
        for (int x = 0; x < w; x += 4) {
            g2.fillRect(x, y, Math.min(2, w - x), 1);
        }
    }
}
