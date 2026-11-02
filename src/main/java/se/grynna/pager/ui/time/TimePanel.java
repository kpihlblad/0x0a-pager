package se.grynna.pager.ui.time;

import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.ui.login.KeyButton;
import se.grynna.pager.ui.time.DateTimeField.Symbol;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The time panel: a full-width drawer above the status bar to go to a time (AT) or set the browsing range
 * (FROM → TO), with a month box and preset chips. Enter applies what the focused field stands for, Esc closes.
 */
public class TimePanel extends JPanel {

    /** What the panel asks its owner to do. */
    public interface Listener {
        void go(Instant at);

        /** @param to {@code null} when the range follows now */
        void setRange(Instant from, Instant to);

        void close();
    }

    /** The field focused when the panel opens. */
    public enum Focus {
        AT, FROM
    }

    static final String GO_TEXT = "go";
    static final String SET_RANGE_TEXT = "set range & go";
    static final String REASON_ORDER = "FROM must be before TO";
    static final String REASON_FUTURE = "TO is in the future";

    private static final String CRUMB = "0x0a pager \u276F";
    private static final String TITLE = "time";
    private static final float SMALL_SIZE = 11f;
    private static final float CHIP_SIZE = 12f;
    private static final int PAD_X = 24;
    private static final int SLIDE_MS = 120;
    private static final int SLIDE_TICK_MS = 15;
    private static final int DEFAULT_WIDTH = 1000;

    private final Font font;
    private final Font smallFont;
    private final Font titleFont;
    private final ZoneId zone;
    private final Clock clock;
    private final Listener listener;

    private final DateTimeField atField;
    private final DateTimeField fromField;
    private final DateTimeField toField;
    private final MonthBox monthBox;
    private final Presets presets;
    private final JPanel body;
    private final JPanel header;
    private final JPanel footer;
    private final JLabel reasonLabel;
    private final KeyButton escButton;
    private final KeyButton enterButton;

    private DateTimeField current;
    private String invalidReason;
    private boolean dockedAtTop;

    private Timer slide;
    private long slideStart;
    private double reveal = 1;

    /**
     * @param font          the monospace base font
     * @param zone          the zone the fields show and edit in
     * @param clock         the clock for now
     * @param parserFactory makes a field's free-text parser from a supplier of its current value (the reference
     *                      date for relative input), e.g. a {@code GotoHandler::parseTimestamp} bound to it
     * @param listener      receives go / set range / close
     */
    public TimePanel(Font font, ZoneId zone, Clock clock,
            Function<Supplier<Instant>, Function<String, Optional<Instant>>> parserFactory, Listener listener) {
        this(font, zone, clock, parserFactory, listener, false);
    }

    /** As above, docked to the top of the log ({@code dockedAtTop}) or to its bottom. */
    public TimePanel(Font font, ZoneId zone, Clock clock,
            Function<Supplier<Instant>, Function<String, Optional<Instant>>> parserFactory, Listener listener,
            boolean dockedAtTop) {
        super(new BorderLayout());
        this.font = font;
        this.smallFont = font.deriveFont(SMALL_SIZE);
        this.titleFont = font.deriveFont(SMALL_SIZE)
                .deriveFont(Map.of(TextAttribute.TRACKING, 0.18f, TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD));
        this.zone = zone;
        this.clock = clock;
        this.listener = listener;
        this.dockedAtTop = dockedAtTop;
        setOpaque(false);
        setBorder(dockedAtTop
                ? BorderFactory.createMatteBorder(0, 0, 1, 0, DarculaColors.G4)
                : BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G4));

        atField = field(parserFactory);
        fromField = field(parserFactory);
        fromField.setAllowedSymbol(Symbol.START);
        toField = field(parserFactory);
        toField.setAllowedSymbol(Symbol.NOW);
        current = atField;

        monthBox = new MonthBox(font, zone, clock);
        monthBox.setOnDayPicked(d -> {
            current.setDate(d);
            current.requestFocusInWindow();
        });

        presets = new Presets();

        Chip startPill = new Chip(Symbol.START.word(), smallFont);
        startPill.addActionListener(e -> pickSymbol(fromField, Symbol.START));
        Chip nowPill = new Chip(Symbol.NOW.word(), smallFont);
        nowPill.addActionListener(e -> pickSymbol(toField, Symbol.NOW));

        JLabel atLabel = label("AT");
        JLabel fromLabel = label("FROM");
        JLabel toLabel = label("TO");
        int labelW = fromLabel.getPreferredSize().width;
        for (JLabel l : List.of(atLabel, fromLabel, toLabel)) {
            l.setPreferredSize(new Dimension(labelW, l.getPreferredSize().height));
        }
        JLabel arrow = new JLabel("\u2192");
        arrow.setFont(font);
        arrow.setForeground(DarculaColors.G6);

        JPanel band = new JPanel(new BandLayout());
        band.setOpaque(false);
        band.add(group(atLabel, atField));
        band.add(new Rule());
        band.add(group(fromLabel, fromField, startPill));
        band.add(arrow);
        band.add(group(toLabel, toField, nowPill));

        body = new JPanel(new BodyLayout(band));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(10, PAD_X, 12, PAD_X));
        body.add(band);
        body.add(presets);
        body.add(new Rule());
        body.add(monthBox);

        header = createHeader();

        escButton = new KeyButton("Esc", "close", font, false);
        escButton.setFocusable(false);
        escButton.addActionListener(e -> listener.close());
        enterButton = new KeyButton("Enter", GO_TEXT, font, true);
        enterButton.setFocusable(false);
        enterButton.addActionListener(e -> commit(current));
        reasonLabel = new JLabel();
        reasonLabel.setFont(smallFont);
        reasonLabel.setForeground(DarculaColors.G7);
        footer = createFooter();

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        for (DateTimeField f : List.of(atField, fromField, toField)) {
            f.setOnCommit(() -> commit(f));
            f.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !e.isConsumed()) {
                        e.consume();
                        listener.close();
                    }
                }
            });
            f.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    setCurrent(f);
                }
            });
        }
        atField.addChangeListener(e -> {
            rebuildPresets();
            followCurrent();
        });
        fromField.addChangeListener(e -> rangeChanged());
        toField.addChangeListener(e -> rangeChanged());

        setFocusCycleRoot(true);
        setFocusTraversalPolicy(new CyclePolicy(List.of(atField, fromField, toField)));

        rebuildPresets();
        rangeChanged();
        updateEnter();
    }

    // ---------------------------------------------------------------- public API

    /**
     * Fills the fields and focuses one; starts the slide-in.
     *
     * @param at   the AT value, {@code null} for now
     * @param from the range start; at or before {@link SearchBoundaries#MIN_MINIMUM_BOUNDARY} (or {@code null})
     *             shows the START symbol, painted as {@code 1971}
     * @param to   the range end, {@code null} when the range follows now
     */
    public void open(Focus focus, Instant at, Instant from, Instant to) {
        atField.setValue(at == null ? clock.instant() : at);
        if (from == null || !from.isAfter(SearchBoundaries.MIN_MINIMUM_BOUNDARY)) {
            fromField.setSymbol(Symbol.START);
        } else {
            fromField.setValue(from);
        }
        if (to == null) {
            toField.setSymbol(Symbol.NOW);
        } else {
            toField.setValue(to);
        }
        clearPreview();
        rebuildPresets();
        rangeChanged();
        setCurrent(focus == Focus.FROM ? fromField : atField);
        current.requestFocusInWindow();
        startSlide();
    }

    /** Docks the panel to the top ({@code true}) or bottom ({@code false}) of the log. */
    public void setDockedAtTop(boolean dockedAtTop) {
        if (this.dockedAtTop == dockedAtTop) {
            return;
        }
        this.dockedAtTop = dockedAtTop;
        setBorder(dockedAtTop
                ? BorderFactory.createMatteBorder(0, 0, 1, 0, DarculaColors.G4)
                : BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G4));
        revalidate();
        repaint();
    }

    /** The height the panel wants at {@code width}; the presets wrap. */
    public int preferredHeight(int width) {
        Insets in = getInsets();
        return in.top + in.bottom + header.getPreferredSize().height
                + ((BodyLayout) body.getLayout()).heightFor(body, width - in.left - in.right)
                + footer.getPreferredSize().height;
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        int width = getWidth() > 0
                ? getWidth()
                : getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : DEFAULT_WIDTH;
        return new Dimension(width, preferredHeight(width));
    }

    // ---------------------------------------------------------------- package API for tests

    DateTimeField atField() {
        return atField;
    }

    DateTimeField fromField() {
        return fromField;
    }

    DateTimeField toField() {
        return toField;
    }

    KeyButton enterButton() {
        return enterButton;
    }

    String reason() {
        return reasonLabel.getText();
    }

    List<Chip> chips() {
        return presets.chips;
    }

    // ---------------------------------------------------------------- behaviour

    private DateTimeField field(Function<Supplier<Instant>, Function<String, Optional<Instant>>> parserFactory) {
        DateTimeField[] self = new DateTimeField[1];
        DateTimeField f = new DateTimeField(font, zone,
                parserFactory.apply(() -> self[0] == null ? clock.instant() : self[0].resolve(clock.instant())));
        self[0] = f;
        f.setClock(clock);
        return f;
    }

    private void setCurrent(DateTimeField f) {
        if (current != f) {
            current = f;
            updateEnter();
        }
        followCurrent();
    }

    private void pickSymbol(DateTimeField f, Symbol symbol) {
        f.setSymbol(symbol);
        setCurrent(f);
        f.requestFocusInWindow();
    }

    private void commit(DateTimeField f) {
        Instant now = clock.instant();
        if (f == atField) {
            listener.go(atField.resolve(now));
        } else if (rangeProblem() == null) {
            listener.setRange(fromField.resolve(now), toField.getSymbol() == Symbol.NOW ? null : toField.getValue());
        }
    }

    /** The reason the range is invalid, or {@code null}. */
    private String rangeProblem() {
        Instant now = clock.instant();
        Instant from = fromField.resolve(now);
        Instant to = toField.resolve(now);
        if (toField.getSymbol() == Symbol.NONE && to.isAfter(now)) {
            return REASON_FUTURE;
        }
        return from.isBefore(to) ? null : REASON_ORDER;
    }

    private void rangeChanged() {
        invalidReason = rangeProblem();
        toField.setInvalid(invalidReason != null);
        monthBox.setRange(fromField.getSymbol() == Symbol.START ? null : date(fromField),
                date(toField));
        followCurrent();
        updateEnter();
    }

    private void updateEnter() {
        boolean range = current != atField;
        enterButton.setText(range ? SET_RANGE_TEXT : GO_TEXT);
        enterButton.setEnabled(!range || invalidReason == null);
        reasonLabel.setText(invalidReason == null ? "" : invalidReason);
        footer.revalidate();
        footer.repaint();
    }

    private void followCurrent() {
        LocalDate day = date(current);
        monthBox.setMonth(YearMonth.from(day));
        monthBox.setFocusDay(day);
    }

    private LocalDate date(DateTimeField f) {
        return f.getLocalDateTime().toLocalDate();
    }

    private void rebuildPresets() {
        Instant at = atField.resolve(clock.instant());
        presets.setRows(TimePresets.rows(at, clock.instant(), zone));
    }

    private void preview(TimePresets.Preset p) {
        fromField.setPreview(p.fromIsStart() ? null : p.from(), p.fromIsStart() ? Symbol.START : Symbol.NONE);
        toField.setPreview(p.toIsNow() ? null : p.to(), p.toIsNow() ? Symbol.NOW : Symbol.NONE);
        monthBox.setPreviewRange(p.fromIsStart() ? null : LocalDate.ofInstant(p.from(), zone),
                LocalDate.ofInstant(p.toIsNow() ? clock.instant() : p.to(), zone));
    }

    private void clearPreview() {
        fromField.setPreview(null);
        toField.setPreview(null);
        monthBox.setPreviewRange(null, null);
    }

    private void apply(TimePresets.Preset p) {
        clearPreview();
        listener.setRange(p.from(), p.to());
    }

    // ---------------------------------------------------------------- slide-in

    private void startSlide() {
        if (slide != null) {
            slide.stop();
        }
        if (GraphicsEnvironment.isHeadless()) {
            reveal = 1;
            return;
        }
        reveal = 0;
        slideStart = System.nanoTime();
        slide = new Timer(SLIDE_TICK_MS, e -> {
            double t = Math.min(1, (System.nanoTime() - slideStart) / (SLIDE_MS * 1_000_000.0));
            reveal = 1 - Math.pow(1 - t, 3);
            if (t >= 1) {
                reveal = 1;
                ((Timer) e.getSource()).stop();
            }
            repaint();
        });
        slide.start();
    }

    @Override
    public void paint(Graphics g) {
        if (reveal >= 1) {
            super.paint(g);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            int offset = (int) Math.round(getHeight() * (1 - reveal));
            g2.translate(0, dockedAtTop ? -offset : offset);
            super.paint(g2);
        } finally {
            g2.dispose();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(DarculaColors.BACKGROUND_PLUS);
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    // ---------------------------------------------------------------- building

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(smallFont.deriveFont(Font.BOLD));
        l.setForeground(DarculaColors.COMMENT);
        return l;
    }

    private static JComponent group(JComponent... parts) {
        Box box = Box.createHorizontalBox();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                box.add(Box.createHorizontalStrut(i == 1 ? 6 : 8));
            }
            box.add(parts[i]);
        }
        return box;
    }

    private JPanel createHeader() {
        JPanel h = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        h.setOpaque(false);
        h.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, DarculaColors.G4),
                new EmptyBorder(10, PAD_X - 8, 8, PAD_X)));
        JLabel crumb = new JLabel(CRUMB);
        crumb.setFont(font);
        crumb.setForeground(DarculaColors.G6);
        JLabel title = new JLabel(TITLE);
        title.setFont(font.deriveFont(Font.BOLD));
        title.setForeground(DarculaColors.FOREGROUND);
        h.add(crumb);
        h.add(title);
        return h;
    }

    private JPanel createFooter() {
        JPanel f = new JPanel(new BorderLayout());
        f.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G3),
                new EmptyBorder(6, PAD_X, 6, PAD_X - 8)));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(reasonLabel);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(escButton);
        buttons.add(enterButton);
        f.add(buttons, BorderLayout.EAST);
        return f;
    }

    // ---------------------------------------------------------------- presets

    /** The preset rows: a tracked small caps title column and wrapping chips. */
    private final class Presets extends JPanel {
        private static final int TITLE_GAP = 12;
        private static final int CHIP_GAP = 6;
        private static final int LINE_GAP = 6;
        private static final int ROW_GAP = 4;

        private record RowParts(JLabel title, List<Chip> chips) {}

        private final List<RowParts> rows = new ArrayList<>();
        private final List<Chip> chips = new ArrayList<>();
        private final Font chipFont = font.deriveFont(CHIP_SIZE);

        Presets() {
            setOpaque(false);
            setLayout(null);
        }

        void setRows(List<TimePresets.Row> model) {
            clearPreview();
            removeAll();
            rows.clear();
            chips.clear();
            for (TimePresets.Row r : model) {
                JLabel title = new JLabel(r.title().toUpperCase());
                title.setFont(titleFont);
                title.setForeground(DarculaColors.G7);
                add(title);
                List<Chip> rowChips = new ArrayList<>();
                for (TimePresets.Preset p : r.presets()) {
                    Chip chip = new Chip(p.label(), chipFont);
                    chip.setToolTipText(tooltip(p));
                    chip.addActionListener(e -> apply(p));
                    chip.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseEntered(MouseEvent e) {
                            preview(p);
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            clearPreview();
                        }
                    });
                    add(chip);
                    rowChips.add(chip);
                    chips.add(chip);
                }
                rows.add(new RowParts(title, rowChips));
            }
            revalidate();
            repaint();
        }

        private String tooltip(TimePresets.Preset p) {
            String from = p.fromIsStart()
                    ? Symbol.START.word()
                    : DateTimeSegments.format(LocalDateTime.ofInstant(p.from(), zone));
            String to = p.toIsNow()
                    ? Symbol.NOW.word()
                    : DateTimeSegments.format(LocalDateTime.ofInstant(p.to(), zone));
            return from + " \u2192 " + to;
        }

        private int titleWidth() {
            // Wide enough for "AROUND 00:00:00", so the chip column does not move as AT changes.
            return getFontMetrics(titleFont).stringWidth("AROUND 00:00:00") + TITLE_GAP;
        }

        /** Lays out (when {@code apply}) and returns the height at {@code width}. */
        int layoutAt(int width, boolean apply) {
            int tw = titleWidth();
            int y = 0;
            for (int r = 0; r < rows.size(); r++) {
                RowParts row = rows.get(r);
                int x = tw;
                int lineH = row.chips().isEmpty() ? 0 : row.chips().getFirst().getPreferredSize().height;
                Dimension td = row.title().getPreferredSize();
                if (apply) {
                    row.title().setBounds(0, y + (lineH - td.height) / 2, tw, td.height);
                }
                for (Chip c : row.chips()) {
                    Dimension d = c.getPreferredSize();
                    if (x > tw && x + d.width > width) {
                        x = tw;
                        y += lineH + LINE_GAP;
                    }
                    if (apply) {
                        c.setBounds(x, y, d.width, d.height);
                    }
                    x += d.width + CHIP_GAP;
                }
                y += lineH + (r < rows.size() - 1 ? LINE_GAP + ROW_GAP : 0);
            }
            return y;
        }

        @Override
        public void doLayout() {
            layoutAt(getWidth(), true);
        }
    }

    // ---------------------------------------------------------------- layouts

    /**
     * The fields band with the month box on its right. The presets go below the band beside the month box, or
     * across the full width below both when that is lower (narrow widths, where the band wraps).
     */
    private final class BodyLayout implements LayoutManager {
        private static final int GAP = 24;
        private static final int BAND_GAP = 10;
        private final JPanel band;

        BodyLayout(JPanel band) {
            this.band = band;
        }

        private record Plan(int leftWidth, int bandH, int presetsY, int presetsW, int presetsH, int topH,
                int height) {}

        private Plan plan(Container c, int width) {
            Insets in = c.getInsets();
            int inner = width - in.left - in.right;
            int monthW = monthBox.getPreferredSize().width;
            int monthH = monthBox.getPreferredSize().height;
            int lw = inner - monthW - GAP;
            int bandH = ((BandLayout) band.getLayout()).heightFor(band, lw);
            int sideH = presets.layoutAt(lw, false);
            int side = Math.max(bandH + BAND_GAP + sideH, monthH);
            int top = Math.max(bandH, monthH);
            int fullH = presets.layoutAt(inner, false);
            int full = top + BAND_GAP + fullH;
            if (side <= full) {
                return new Plan(lw, bandH, bandH + BAND_GAP, lw, sideH, side, in.top + in.bottom + side);
            }
            return new Plan(lw, bandH, top + BAND_GAP, inner, fullH, top, in.top + in.bottom + full);
        }

        int heightFor(Container c, int width) {
            return plan(c, width).height();
        }

        @Override
        public void layoutContainer(Container c) {
            Insets in = c.getInsets();
            Plan p = plan(c, c.getWidth());
            band.setBounds(in.left, in.top, p.leftWidth(), p.bandH());
            presets.setBounds(in.left, in.top + p.presetsY(), p.presetsW(), p.presetsH());
            Dimension md = monthBox.getPreferredSize();
            int mx = c.getWidth() - in.right - md.width;
            monthBox.setBounds(mx, in.top, md.width, md.height);
            Component rule = c.getComponent(2);
            rule.setBounds(mx - GAP / 2, in.top, 1, p.topH());
        }

        @Override
        public Dimension preferredLayoutSize(Container c) {
            int w = c.getWidth() > 0 ? c.getWidth() : DEFAULT_WIDTH;
            return new Dimension(w, heightFor(c, w));
        }

        @Override
        public Dimension minimumLayoutSize(Container c) {
            return preferredLayoutSize(c);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {}

        @Override
        public void removeLayoutComponent(Component comp) {}
    }

    /**
     * {@code AT │ FROM → TO} on one line when it fits, else AT above {@code FROM → TO}, else one field per line.
     * Children: AT group, separator, FROM group, arrow, TO group.
     */
    private static final class BandLayout implements LayoutManager {
        private static final int GAP = 14;
        private static final int ARROW_GAP = 8;
        private static final int LINE_GAP = 2;

        private static int[][] lines(Container c, int width) {
            Dimension at = c.getComponent(0).getPreferredSize();
            Dimension from = c.getComponent(2).getPreferredSize();
            Dimension arrow = c.getComponent(3).getPreferredSize();
            Dimension to = c.getComponent(4).getPreferredSize();
            int range = from.width + ARROW_GAP + arrow.width + ARROW_GAP + to.width;
            if (at.width + 2 * GAP + 1 + range <= width) {
                return new int[][]{{0, 1, 2, 3, 4}};
            }
            if (range <= width) {
                return new int[][]{{0}, {2, 3, 4}};
            }
            return new int[][]{{0}, {2, 3}, {4}};
        }

        private static int lineHeight(Container c) {
            int h = 0;
            for (Component k : c.getComponents()) {
                h = Math.max(h, k.getPreferredSize().height);
            }
            return h;
        }

        int heightFor(Container c, int width) {
            int n = lines(c, width).length;
            return n * lineHeight(c) + (n - 1) * LINE_GAP;
        }

        @Override
        public void layoutContainer(Container c) {
            int lh = lineHeight(c);
            int[][] lines = lines(c, c.getWidth());
            c.getComponent(1).setVisible(lines.length == 1);
            int y = 0;
            for (int[] line : lines) {
                int x = 0;
                for (int idx : line) {
                    Component k = c.getComponent(idx);
                    Dimension d = k.getPreferredSize();
                    if (idx == 1) {
                        x += GAP;
                        k.setBounds(x, y + 6, 1, lh - 12);
                        x += 1 + GAP;
                        continue;
                    }
                    if (idx == 3 || (idx == 4 && x > 0)) {
                        x += ARROW_GAP;
                    }
                    k.setBounds(x, y + (lh - d.height) / 2, d.width, d.height);
                    x += d.width;
                }
                y += lh + LINE_GAP;
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container c) {
            return new Dimension(c.getWidth(), heightFor(c, c.getWidth()));
        }

        @Override
        public Dimension minimumLayoutSize(Container c) {
            return preferredLayoutSize(c);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {}

        @Override
        public void removeLayoutComponent(Component comp) {}
    }

    // ---------------------------------------------------------------- small parts

    /** A pill button in the {@link KeyButton} look: a preset chip or the {@code start} / {@code now} button. */
    static final class Chip extends JButton {
        private static final int PAD_X = 9;
        private static final int PAD_Y = 3;

        Chip(String text, Font font) {
            super(text);
            setFont(font);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(getText()) + 2 * PAD_X, fm.getHeight() + 2 * PAD_Y);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                ButtonModel m = getModel();
                boolean hot = m.isRollover() || m.isPressed();
                int h = getHeight();
                if (hot) {
                    g2.setColor(m.isPressed() ? DarculaColors.BLUE : DarculaColors.G2);
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, h, h));
                }
                g2.setColor(hot ? DarculaColors.G7 : DarculaColors.G5);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, h - 1f, h - 1f, h - 1f));
                FontMetrics fm = g2.getFontMetrics(getFont());
                g2.setFont(getFont());
                g2.setColor(hot ? DarculaColors.G9 : DarculaColors.G8);
                g2.drawString(getText(), PAD_X, (h - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** A one-pixel vertical hairline. */
    private static final class Rule extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 0);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(DarculaColors.G4);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    /** Tab / Shift-Tab cycle AT → FROM → TO. */
    private static final class CyclePolicy extends FocusTraversalPolicy {
        private final List<? extends Component> order;

        CyclePolicy(List<? extends Component> order) {
            this.order = order;
        }

        private int index(Component c) {
            for (int i = 0; i < order.size(); i++) {
                if (SwingUtilities.isDescendingFrom(c, order.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Component getComponentAfter(Container root, Component c) {
            return order.get((index(c) + 1) % order.size());
        }

        @Override
        public Component getComponentBefore(Container root, Component c) {
            int i = index(c);
            return order.get(((i < 0 ? 0 : i) - 1 + order.size()) % order.size());
        }

        @Override
        public Component getFirstComponent(Container root) {
            return order.getFirst();
        }

        @Override
        public Component getLastComponent(Container root) {
            return order.getLast();
        }

        @Override
        public Component getDefaultComponent(Container root) {
            return order.getFirst();
        }
    }
}
