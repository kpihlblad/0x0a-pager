package se.grynna.pager.ui.time;

import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A 7 × 6 day grid, ISO weeks with Monday first, under a {@code ‹ May 2017 ›} header. It shades the range,
 * outlines the focused field's day in orange and underlines today. Wheel or the header arrows step months; a day
 * click reports the day. Never focusable: the time panel keeps the keyboard in its fields.
 */
public class MonthBox extends JComponent {

    private static final int PAD = 8;
    private static final int CELL_PAD_X = 6;
    private static final int CELL_PAD_Y = 3;
    private static final int HEADER_GAP = 6;
    private static final int ARC = 8;
    private static final int ARROW_W = 4;
    private static final int ARROW_H = 7;

    private enum Hover {
        PREVIOUS, NEXT, DAY
    }

    private final Font font;
    private final Font smallFont;
    private final ZoneId zone;
    private final Clock clock;

    private YearMonth month;
    private LocalDate rangeFrom;
    private LocalDate rangeTo;
    private boolean hasRange;
    private LocalDate previewFrom;
    private LocalDate previewTo;
    private boolean hasPreview;
    private LocalDate focusDay;
    private Consumer<LocalDate> onDayPicked = d -> {};

    private Hover hover;
    private LocalDate hoverDay;
    private double wheelAccumulator;

    /**
     * @param font  the monospace font
     * @param zone  the zone that decides which day is today
     * @param clock the clock for today
     */
    public MonthBox(Font font, ZoneId zone, Clock clock) {
        this.font = font;
        this.smallFont = font.deriveFont(font.getSize2D() - 2f);
        this.zone = zone;
        this.clock = clock;
        this.month = YearMonth.from(today());
        setFont(font);
        setFocusable(false);
        setOpaque(false);
        Mouse mouse = new Mouse();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public YearMonth getMonth() {
        return month;
    }

    public void setMonth(YearMonth month) {
        Objects.requireNonNull(month, "month");
        if (!month.equals(this.month)) {
            this.month = month;
            hoverDay = null;
            repaint();
        }
    }

    /**
     * The shaded days, inclusive. A {@code null} end is open on that side ({@code start} / {@code now}); both
     * {@code null} shade nothing.
     */
    public void setRange(LocalDate from, LocalDate to) {
        rangeFrom = from;
        rangeTo = to;
        hasRange = from != null || to != null;
        repaint();
    }

    /** A dimmer range shown instead of the range while a preset is hovered; both {@code null} clear it. */
    public void setPreviewRange(LocalDate from, LocalDate to) {
        previewFrom = from;
        previewTo = to;
        hasPreview = from != null || to != null;
        repaint();
    }

    /** The focused field's day, outlined in orange; {@code null} for none. */
    public void setFocusDay(LocalDate day) {
        if (!Objects.equals(focusDay, day)) {
            focusDay = day;
            repaint();
        }
    }

    public void setOnDayPicked(Consumer<LocalDate> onDayPicked) {
        this.onDayPicked = onDayPicked == null ? d -> {} : onDayPicked;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private LocalDate firstCell() {
        return month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ---------------------------------------------------------------- geometry

    private int cellWidth() {
        return getFontMetrics(font).charWidth('0') * 2 + 2 * CELL_PAD_X;
    }

    private int cellHeight() {
        return getFontMetrics(font).getHeight() + 2 * CELL_PAD_Y;
    }

    private int headerHeight() {
        return getFontMetrics(font).getHeight() + 2;
    }

    private int weekdayHeight() {
        return getFontMetrics(smallFont).getHeight() + 2;
    }

    private int gridX() {
        return (getWidth() - 7 * cellWidth()) / 2;
    }

    private int gridY() {
        return PAD + headerHeight() + HEADER_GAP + weekdayHeight();
    }

    private Rectangle cell(int index) {
        int cw = cellWidth();
        int ch = cellHeight();
        return new Rectangle(gridX() + (index % 7) * cw, gridY() + (index / 7) * ch, cw, ch);
    }

    /** The day under a point, or {@code null}. */
    LocalDate dayAt(int x, int y) {
        int cw = cellWidth();
        int ch = cellHeight();
        int col = Math.floorDiv(x - gridX(), cw);
        int row = Math.floorDiv(y - gridY(), ch);
        if (col < 0 || col > 6 || row < 0 || row > 5) {
            return null;
        }
        return firstCell().plusDays(row * 7L + col);
    }

    private Rectangle previousArrow() {
        return new Rectangle(gridX(), PAD, cellWidth(), headerHeight());
    }

    private Rectangle nextArrow() {
        return new Rectangle(gridX() + 6 * cellWidth(), PAD, cellWidth(), headerHeight());
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        return new Dimension(2 * PAD + 7 * cellWidth(),
                PAD + headerHeight() + HEADER_GAP + weekdayHeight() + 6 * cellHeight() + PAD);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    // ---------------------------------------------------------------- mouse

    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            if (previousArrow().contains(e.getPoint())) {
                setMonth(month.minusMonths(1));
            } else if (nextArrow().contains(e.getPoint())) {
                setMonth(month.plusMonths(1));
            } else {
                LocalDate day = dayAt(e.getX(), e.getY());
                if (day != null) {
                    onDayPicked.accept(day);
                }
            }
            updateHover(e.getX(), e.getY());
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            updateHover(e.getX(), e.getY());
        }

        @Override
        public void mouseExited(MouseEvent e) {
            setHover(null, null);
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            double rotation = e.getPreciseWheelRotation();
            if (Math.signum(rotation) != Math.signum(wheelAccumulator)) {
                wheelAccumulator = 0;
            }
            wheelAccumulator += rotation;
            int notches = (int) wheelAccumulator;
            if (notches != 0) {
                wheelAccumulator -= notches;
                setMonth(month.plusMonths(-notches));
                updateHover(e.getX(), e.getY());
            }
            e.consume();
        }

        private void updateHover(int x, int y) {
            if (previousArrow().contains(x, y)) {
                setHover(Hover.PREVIOUS, null);
            } else if (nextArrow().contains(x, y)) {
                setHover(Hover.NEXT, null);
            } else {
                LocalDate day = dayAt(x, y);
                setHover(day == null ? null : Hover.DAY, day);
            }
        }
    }

    private void setHover(Hover h, LocalDate day) {
        if (h != hover || !Objects.equals(day, hoverDay)) {
            hover = h;
            hoverDay = day;
            setCursor(h == null ? null : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            repaint();
        }
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paintHeader(g2);
            paintWeekdays(g2);
            paintRange(g2);
            paintDays(g2);
        } finally {
            g2.dispose();
        }
    }

    private void paintHeader(Graphics2D g2) {
        g2.setFont(font.deriveFont(Font.BOLD));
        FontMetrics fm = g2.getFontMetrics();
        String title = month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
        int baseline = PAD + (headerHeight() - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(DarculaColors.G8);
        g2.drawString(title, (getWidth() - fm.stringWidth(title)) / 2, baseline);

        Rectangle prev = previousArrow();
        Rectangle next = nextArrow();
        if (hover == Hover.PREVIOUS) {
            g2.setColor(DarculaColors.HOVER_FILL);
            g2.fill(roundCell(prev));
        }
        if (hover == Hover.NEXT) {
            g2.setColor(DarculaColors.HOVER_FILL);
            g2.fill(roundCell(next));
        }
        g2.setColor(hover == Hover.PREVIOUS ? DarculaColors.G9 : DarculaColors.G6);
        g2.fill(chevron(prev, true));
        g2.setColor(hover == Hover.NEXT ? DarculaColors.G9 : DarculaColors.G6);
        g2.fill(chevron(next, false));
    }

    private static Shape chevron(Rectangle r, boolean left) {
        float cx = r.x + r.width / 2f;
        float cy = r.y + r.height / 2f;
        Path2D.Float p = new Path2D.Float();
        float half = ARROW_W / 2f;
        if (left) {
            p.moveTo(cx + half, cy - ARROW_H / 2f);
            p.lineTo(cx + half, cy + ARROW_H / 2f);
            p.lineTo(cx - half, cy);
        } else {
            p.moveTo(cx - half, cy - ARROW_H / 2f);
            p.lineTo(cx - half, cy + ARROW_H / 2f);
            p.lineTo(cx + half, cy);
        }
        p.closePath();
        return p;
    }

    private void paintWeekdays(Graphics2D g2) {
        g2.setFont(smallFont);
        FontMetrics fm = g2.getFontMetrics();
        int y = PAD + headerHeight() + HEADER_GAP + fm.getAscent();
        int cw = cellWidth();
        for (int i = 0; i < 7; i++) {
            String name = DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2);
            g2.setColor(i >= 5 ? DarculaColors.G6 : DarculaColors.G7);
            g2.drawString(name, gridX() + i * cw + (cw - fm.stringWidth(name)) / 2, y);
        }
        g2.setColor(DarculaColors.G3);
        g2.fillRect(gridX() + 2, gridY() - 2, 7 * cw - 4, 1);
    }

    private boolean inShown(LocalDate d) {
        LocalDate from = hasPreview ? previewFrom : rangeFrom;
        LocalDate to = hasPreview ? previewTo : rangeTo;
        if (!hasPreview && !hasRange) {
            return false;
        }
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
    }

    private void paintRange(Graphics2D g2) {
        if (!hasPreview && !hasRange) {
            return;
        }
        g2.setColor(hasPreview ? DarculaColors.PREVIEW_FILL : DarculaColors.RANGE_FILL);
        LocalDate first = firstCell();
        for (int row = 0; row < 6; row++) {
            int c0 = -1;
            int c1 = -1;
            for (int col = 0; col < 7; col++) {
                if (inShown(first.plusDays(row * 7L + col))) {
                    c0 = c0 < 0 ? col : c0;
                    c1 = col;
                }
            }
            if (c0 < 0) {
                continue;
            }
            LocalDate d0 = first.plusDays(row * 7L + c0);
            LocalDate d1 = first.plusDays(row * 7L + c1);
            boolean roundLeft = !inShown(d0.minusDays(1));
            boolean roundRight = !inShown(d1.plusDays(1));
            Rectangle a = cell(row * 7 + c0);
            Rectangle b = cell(row * 7 + c1);
            float x0 = a.x + (roundLeft ? 2 : 0);
            float x1 = b.x + b.width - (roundRight ? 2 : 0);
            float y = a.y + 2;
            float h = a.height - 4;
            Area band = new Area(new RoundRectangle2D.Float(x0, y, x1 - x0, h, ARC, ARC));
            float mid = (x0 + x1) / 2f;
            if (!roundLeft) {
                band.add(new Area(new Rectangle2D.Float(x0, y, mid - x0, h)));
            }
            if (!roundRight) {
                band.add(new Area(new Rectangle2D.Float(mid, y, x1 - mid, h)));
            }
            g2.fill(band);
        }
    }

    private void paintDays(Graphics2D g2) {
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        LocalDate first = firstCell();
        LocalDate today = today();
        for (int i = 0; i < 42; i++) {
            LocalDate d = first.plusDays(i);
            Rectangle r = cell(i);
            if (hover == Hover.DAY && d.equals(hoverDay)) {
                g2.setColor(DarculaColors.HOVER_FILL);
                g2.fill(roundCell(r));
            }
            if (d.equals(focusDay)) {
                g2.setColor(DarculaColors.ORANGE);
                g2.draw(new RoundRectangle2D.Float(r.x + 1.5f, r.y + 1.5f, r.width - 3f, r.height - 3f, ARC, ARC));
            }
            boolean inMonth = YearMonth.from(d).equals(month);
            Color text;
            if (!inMonth) {
                text = DarculaColors.G6;
            } else if (d.equals(hoverDay) || inShown(d)) {
                text = DarculaColors.FOREGROUND;
            } else {
                text = DarculaColors.G8;
            }
            String label = String.valueOf(d.getDayOfMonth());
            int tw = fm.stringWidth(label);
            int tx = r.x + (r.width - tw) / 2;
            int baseline = r.y + CELL_PAD_Y + fm.getAscent();
            g2.setColor(text);
            g2.drawString(label, tx, baseline);
            if (d.equals(today)) {
                g2.fillRect(tx, baseline + 2, tw, 1);
            }
        }
    }

    private static Shape roundCell(Rectangle r) {
        return new RoundRectangle2D.Float(r.x + 2, r.y + 2, r.width - 4, r.height - 4, ARC, ARC);
    }
}
