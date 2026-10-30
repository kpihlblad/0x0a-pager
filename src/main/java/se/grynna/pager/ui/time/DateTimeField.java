package se.grynna.pager.ui.time;

import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.ui.time.DateTimeSegments.Segment;
import se.grynna.pager.ui.time.DateTimeSegments.TypingState;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A painted segmented editor of {@code yyyy-MM-dd HH:mm:ss}. Each segment has a ▲ zone above and a ▼
 * zone below; the mouse wheel steps the segment under the pointer, keys step the focused one, digits overwrite it.
 * A letter, {@code +} or a paste switches to free text, parsed on Enter or focus loss. The field may stand for a
 * symbol ({@code now} / {@code 1971}) instead of a time; any step or edit makes it concrete.
 */
public class DateTimeField extends JComponent {

    /** What the field stands for when it is not a concrete time. */
    public enum Symbol {
        NONE(""), NOW("now"), START(SearchBoundaries.MIN_BOUNDARY_YEAR);

        /** The legacy word still accepted as free text for {@link #START}. */
        private static final String LEGACY_START_WORD = "start";

        private final String word;

        Symbol(String word) {
            this.word = word;
        }

        public String word() {
            return word;
        }

        /** Whether free text {@code input} names this symbol: its word, plus the legacy {@code start} for START. */
        public boolean namedBy(String input) {
            return word.equalsIgnoreCase(input) || (this == START && LEGACY_START_WORD.equalsIgnoreCase(input));
        }
    }

    enum Zone {
        UP, TEXT, DOWN
    }

    private static final int PAD_X = 9;
    private static final int PAD_Y = 4;
    private static final int ARROW_ZONE = 10;
    private static final int ARROW_W = 7;
    private static final int ARROW_H = 4;
    private static final int SEG_PAD = 2;
    private static final int BOX_ARC = 8;
    private static final int SEG_ARC = 5;
    private static final int REPEAT_DELAY_MS = 400;
    private static final int REPEAT_INTERVAL_MS = 55;
    private static final Color BOX_FILL = DarculaColors.BACKGROUND.darker();

    private final Font font;
    private final ZoneId zone;
    private final Function<String, Optional<Instant>> freeTextParser;
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private Clock clock = Clock.systemUTC();

    private TypingState state;
    private Instant exact;
    private Symbol symbol = Symbol.NONE;
    private Symbol allowedSymbol = Symbol.NONE;
    private boolean invalid;

    private boolean freeText;
    private boolean freeTextInvalid;
    private final StringBuilder text = new StringBuilder();
    private int caret;

    private Instant previewValue;
    private Symbol previewSymbol = Symbol.NONE;

    private Segment hoverSegment;
    private Zone hoverZone;
    private Segment pressedSegment;
    private Zone pressedZone;
    private double wheelAccumulator;
    private Segment wheelSegment;
    private final Timer repeat;
    private int repeatDelta;

    private Runnable onCommit;

    /**
     * @param font           the monospace font
     * @param zone           the zone the field shows and edits in
     * @param freeTextParser parses free text (e.g. {@code GotoHandler::parseTimestamp}); empty when unparsable
     */
    public DateTimeField(Font font, ZoneId zone, Function<String, Optional<Instant>> freeTextParser) {
        this.font = font;
        this.zone = zone;
        this.freeTextParser = freeTextParser;
        Instant now = clock.instant();
        this.exact = now;
        this.state = TypingState.of(LocalDateTime.ofInstant(now, zone), Segment.HOUR);
        setFont(font);
        setFocusable(true);
        setOpaque(false);
        setRequestFocusEnabled(true);

        repeat = new Timer(REPEAT_INTERVAL_MS, e -> {
            if (pressedSegment != null) {
                stepSegment(pressedSegment, repeatDelta, true);
            }
        });
        repeat.setInitialDelay(REPEAT_DELAY_MS);

        addKeyListener(new Keys());
        Mouse mouse = new Mouse();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                stopRepeat();
                if (freeText) {
                    parseFreeText(false);
                } else {
                    update(TypingState::commit, false);
                }
                repaint();
            }
        });
    }

    // ---------------------------------------------------------------- public API

    /** The time shown, or {@code null} while the field stands for a symbol. */
    public Instant getValue() {
        return symbol == Symbol.NONE ? exact : null;
    }

    /** Sets a concrete time (kept at full precision until edited) and leaves free text and symbols. */
    public void setValue(Instant value) {
        Objects.requireNonNull(value, "value");
        Snapshot before = snapshot();
        symbol = Symbol.NONE;
        exact = value;
        state = state.withValue(LocalDateTime.ofInstant(value, zone));
        leaveFreeText();
        changed(before);
    }

    public Symbol getSymbol() {
        return symbol;
    }

    /** Makes the field stand for {@code symbol}; {@link Symbol#NONE} turns a symbol into the time it stands for. */
    public void setSymbol(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol == Symbol.NONE) {
            if (this.symbol != Symbol.NONE) {
                setValue(resolve(clock.instant()));
            }
            return;
        }
        Snapshot before = snapshot();
        this.symbol = symbol;
        state = state.withValue(state.value());
        leaveFreeText();
        changed(before);
    }

    /** The symbol free text may name ({@code now} on TO, {@code 1971} on FROM). */
    public void setAllowedSymbol(Symbol symbol) {
        this.allowedSymbol = Objects.requireNonNull(symbol, "symbol");
    }

    public Symbol getAllowedSymbol() {
        return allowedSymbol;
    }

    /** The time the field stands for: {@code now} for NOW, the minimum search boundary for START. */
    public Instant resolve(Instant now) {
        return switch (symbol) {
            case NOW -> now;
            case START -> SearchBoundaries.MIN_MINIMUM_BOUNDARY;
            case NONE -> exact;
        };
    }

    /** The shown time in the field's zone, a symbol resolved against the field's clock. */
    public LocalDateTime getLocalDateTime() {
        return LocalDateTime.ofInstant(resolve(clock.instant()), zone);
    }

    /** Sets the date and keeps the time of day; a symbol becomes that date at 00:00:00 (month box click). */
    public void setDate(LocalDate date) {
        LocalDateTime base = symbol == Symbol.NONE ? state.value() : date.atStartOfDay();
        setValue(base.with(date).atZone(zone).toInstant());
    }

    /** A red outline, e.g. for an invalid range. */
    public void setInvalid(boolean invalid) {
        if (this.invalid != invalid) {
            this.invalid = invalid;
            repaint();
        }
    }

    public boolean isInvalid() {
        return invalid;
    }

    /** Shows {@code preview} dim instead of the value (preset hover); {@code null} clears it. */
    public void setPreview(Instant preview) {
        setPreview(preview, Symbol.NONE);
    }

    /** Shows a dim preview: the symbol's word, else {@code preview}; both empty clear it. */
    public void setPreview(Instant preview, Symbol previewSymbol) {
        Symbol ps = previewSymbol == null ? Symbol.NONE : previewSymbol;
        if (!Objects.equals(previewValue, preview) || this.previewSymbol != ps) {
            this.previewValue = preview;
            this.previewSymbol = ps;
            repaint();
        }
    }

    public boolean hasPreview() {
        return previewValue != null || previewSymbol != Symbol.NONE;
    }

    public void addChangeListener(ChangeListener l) {
        listeners.add(l);
    }

    public void removeChangeListener(ChangeListener l) {
        listeners.remove(l);
    }

    /** Runs on Enter (after a successful parse in free text). */
    public void setOnCommit(Runnable onCommit) {
        this.onCommit = onCommit;
    }

    public boolean isFreeText() {
        return freeText;
    }

    /** The free text being edited, empty outside free text. */
    public String getFreeText() {
        return freeText ? text.toString() : "";
    }

    public Segment getFocusedSegment() {
        return state.segment();
    }

    public void setFocusedSegment(Segment segment) {
        update(s -> s.moveTo(segment), false);
    }

    /** The clock that resolves {@code now} when a symbol becomes concrete. */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---------------------------------------------------------------- editing

    private record Snapshot(Instant exact, Symbol symbol) {}

    private Snapshot snapshot() {
        return new Snapshot(exact, symbol);
    }

    private void changed(Snapshot before) {
        repaint();
        if (!Objects.equals(before.exact(), exact) || before.symbol() != symbol) {
            ChangeEvent e = new ChangeEvent(this);
            for (ChangeListener l : listeners) {
                l.stateChanged(e);
            }
        }
    }

    /**
     * Applies a typing-model operation. With {@code concretize}, a symbol first becomes the time it stands for.
     */
    private void update(UnaryOperator<TypingState> op, boolean concretize) {
        Snapshot before = snapshot();
        if (symbol != Symbol.NONE) {
            if (!concretize) {
                state = new TypingState(state.value(), op.apply(state).segment(), "", false);
                repaint();
                return;
            }
            Instant resolved = resolve(clock.instant());
            symbol = Symbol.NONE;
            exact = resolved;
            state = state.withValue(LocalDateTime.ofInstant(resolved, zone));
        }
        TypingState next = op.apply(state);
        if (!next.value().equals(state.value()) || before.symbol() != Symbol.NONE) {
            exact = next.value().atZone(zone).toInstant();
        }
        state = next;
        changed(before);
    }

    private void stepSegment(Segment segment, int delta, boolean focusIt) {
        update(s -> {
            TypingState committed = focusIt ? s.moveTo(segment) : s.commit();
            return committed.withValue(DateTimeSegments.step(committed.value(), segment, delta));
        }, true);
    }

    private void stepLarger(Segment segment, int delta) {
        update(s -> {
            TypingState committed = s.commit();
            return committed.withValue(DateTimeSegments.stepLarger(committed.value(), segment, delta));
        }, true);
    }

    private void enterFreeText(String initial) {
        update(TypingState::commit, false);
        freeText = true;
        freeTextInvalid = false;
        text.setLength(0);
        text.append(initial);
        caret = text.length();
        repaint();
    }

    private void leaveFreeText() {
        freeText = false;
        freeTextInvalid = false;
        text.setLength(0);
        caret = 0;
    }

    private boolean parseFreeText(boolean commit) {
        String input = text.toString().trim();
        if (input.isEmpty()) {
            leaveFreeText();
            repaint();
            return false;
        }
        if (allowedSymbol != Symbol.NONE && allowedSymbol.namedBy(input)) {
            setSymbol(allowedSymbol);
        } else {
            Optional<Instant> parsed = freeTextParser.apply(input);
            if (parsed.isEmpty()) {
                freeTextInvalid = true;
                repaint();
                return false;
            }
            setValue(parsed.get());
        }
        if (commit && onCommit != null) {
            onCommit.run();
        }
        return true;
    }

    private void insert(String s) {
        text.insert(caret, s);
        caret += s.length();
        freeTextInvalid = false;
        repaint();
    }

    private static String clipboardText() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return data instanceof String s ? s.replaceAll("\\s+", " ").trim() : "";
        } catch (Exception | Error e) {
            return "";
        }
    }

    private final class Keys extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            if (freeText) {
                freeTextPressed(e);
            } else {
                segmentPressed(e);
            }
        }

        private void segmentPressed(KeyEvent e) {
            int code = e.getKeyCode();
            boolean shift = e.isShiftDown();
            Segment seg = state.segment();
            switch (code) {
                case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> stepSegment(seg, shift ? 10 : 1, true);
                case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> stepSegment(seg, shift ? -10 : -1, true);
                case KeyEvent.VK_PAGE_UP -> stepLarger(seg, 1);
                case KeyEvent.VK_PAGE_DOWN -> stepLarger(seg, -1);
                case KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> update(s -> s.moveTo(seg.previous()), false);
                case KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> update(s -> s.moveTo(seg.next()), false);
                case KeyEvent.VK_HOME -> update(s -> s.moveTo(Segment.first()), false);
                case KeyEvent.VK_END -> update(s -> s.moveTo(Segment.last()), false);
                case KeyEvent.VK_BACK_SPACE -> update(TypingState::backspace, false);
                case KeyEvent.VK_ENTER -> {
                    update(TypingState::commit, false);
                    if (onCommit != null) {
                        onCommit.run();
                    } else {
                        return;
                    }
                }
                case KeyEvent.VK_ESCAPE -> {
                    if (state.isTyping()) {
                        state = state.withValue(state.value());
                        repaint();
                    }
                    return; // not consumed: the panel closes on Esc
                }
                case KeyEvent.VK_V -> {
                    if (!isPasteShortcut(e)) {
                        return;
                    }
                    paste();
                }
                case KeyEvent.VK_INSERT -> {
                    if (!shift) {
                        return;
                    }
                    paste();
                }
                default -> {
                    return;
                }
            }
            e.consume();
        }

        private void freeTextPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_ENTER -> parseFreeText(true);
                case KeyEvent.VK_ESCAPE -> {
                    leaveFreeText();
                    repaint();
                }
                case KeyEvent.VK_BACK_SPACE -> {
                    if (caret > 0) {
                        text.deleteCharAt(--caret);
                        freeTextInvalid = false;
                    }
                }
                case KeyEvent.VK_DELETE -> {
                    if (caret < text.length()) {
                        text.deleteCharAt(caret);
                        freeTextInvalid = false;
                    }
                }
                case KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> caret = Math.max(0, caret - 1);
                case KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> caret = Math.min(text.length(), caret + 1);
                case KeyEvent.VK_HOME -> caret = 0;
                case KeyEvent.VK_END -> caret = text.length();
                case KeyEvent.VK_V -> {
                    if (!isPasteShortcut(e)) {
                        return;
                    }
                    insert(clipboardText());
                }
                default -> {
                    return;
                }
            }
            repaint();
            e.consume();
        }

        @Override
        public void keyTyped(KeyEvent e) {
            char c = e.getKeyChar();
            if (c == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(c)
                    || (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK
                            | InputEvent.ALT_DOWN_MASK)) != 0) {
                return;
            }
            if (freeText) {
                insert(String.valueOf(c));
            } else if (c >= '0' && c <= '9') {
                update(s -> s.digit(c), true);
            } else if (DateTimeSegments.isSeparator(c)) {
                update(TypingState::separator, false);
            } else if (Character.isLetter(c) || c == '+') {
                enterFreeText(String.valueOf(c));
            } else {
                return;
            }
            e.consume();
        }

        private boolean isPasteShortcut(KeyEvent e) {
            return (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
        }

        private void paste() {
            String s = clipboardText();
            if (!s.isEmpty()) {
                enterFreeText(s);
            }
        }
    }

    // ---------------------------------------------------------------- mouse

    private void stopRepeat() {
        repeat.stop();
        pressedSegment = null;
        pressedZone = null;
    }

    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            if (!hasFocus()) {
                requestFocusInWindow();
            }
            if (freeText) {
                caret = Math.max(0, Math.min(text.length(), Math.round((e.getX() - textX()) / (float) charWidth())));
                repaint();
                return;
            }
            Segment seg = segmentAtX(e.getX());
            Zone z = zoneAtY(e.getY());
            if (z == Zone.TEXT) {
                setFocusedSegment(seg);
                return;
            }
            pressedSegment = seg;
            pressedZone = z;
            repeatDelta = z == Zone.UP ? 1 : -1;
            stepSegment(seg, repeatDelta, true);
            repeat.restart();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (pressedSegment != null) {
                stopRepeat();
                repaint();
            }
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            hover(e.getX(), e.getY());
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            hover(e.getX(), e.getY());
            if (pressedSegment != null && (hoverSegment != pressedSegment || hoverZone != pressedZone)) {
                repeat.stop();
            }
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (pressedSegment == null) {
                setHover(null, null);
            } else {
                repeat.stop();
            }
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (freeText) {
                return;
            }
            Segment seg = segmentAtX(e.getX());
            double rotation = e.getPreciseWheelRotation();
            if (seg != wheelSegment || Math.signum(rotation) != Math.signum(wheelAccumulator)) {
                wheelSegment = seg;
                wheelAccumulator = 0;
            }
            wheelAccumulator += rotation;
            int notches = (int) wheelAccumulator;
            if (notches != 0) {
                wheelAccumulator -= notches;
                stepSegment(seg, -notches, false);
            }
            e.consume();
        }

        private void hover(int x, int y) {
            if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
                setHover(null, null);
            } else {
                setHover(segmentAtX(x), zoneAtY(y));
            }
        }
    }

    private void setHover(Segment seg, Zone z) {
        if (seg != hoverSegment || z != hoverZone) {
            hoverSegment = seg;
            hoverZone = z;
            boolean arrow = z == Zone.UP || z == Zone.DOWN;
            setCursor(arrow && !freeText
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : z == Zone.TEXT && freeText ? Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) : null);
            repaint();
        }
    }

    // ---------------------------------------------------------------- geometry

    private FontMetrics metrics() {
        return getFontMetrics(font);
    }

    private int charWidth() {
        return metrics().charWidth('0');
    }

    private int textX() {
        return PAD_X;
    }

    private int boxY() {
        return ARROW_ZONE;
    }

    private int boxHeight() {
        return metrics().getHeight() + 2 * PAD_Y;
    }

    Segment segmentAtX(int x) {
        int column = Math.floorDiv(x - textX(), charWidth());
        return DateTimeSegments.segmentAt(Math.max(0, column));
    }

    Zone zoneAtY(int y) {
        if (y < boxY()) {
            return Zone.UP;
        }
        return y >= boxY() + boxHeight() ? Zone.DOWN : Zone.TEXT;
    }

    /** The centre x of a segment, for tests and the panel. */
    int segmentCenterX(Segment s) {
        return textX() + (s.start() + s.end()) * charWidth() / 2;
    }

    int textCenterY() {
        return boxY() + boxHeight() / 2;
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        return new Dimension(2 * PAD_X + DateTimeSegments.LENGTH * charWidth(), 2 * ARROW_ZONE + boxHeight());
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            boolean focused = hasFocus();
            int w = getWidth();
            int boxY = boxY();
            int boxH = boxHeight();

            g2.setColor(BOX_FILL);
            g2.fill(new RoundRectangle2D.Float(0, boxY, w, boxH, BOX_ARC, BOX_ARC));
            g2.setColor(outlineColor(focused));
            g2.draw(new RoundRectangle2D.Float(0.5f, boxY + 0.5f, w - 1f, boxH - 1f, BOX_ARC, BOX_ARC));

            int baseline = boxY + PAD_Y + fm.getAscent();
            Shape clip = g2.getClip();
            g2.clipRect(2, boxY + 1, w - 4, boxH - 2);
            if (hasPreview()) {
                g2.setColor(DarculaColors.G6);
                String preview = previewSymbol != Symbol.NONE
                        ? previewSymbol.word()
                        : DateTimeSegments.format(LocalDateTime.ofInstant(previewValue, zone));
                g2.drawString(preview, textX(), baseline);
            } else if (freeText) {
                paintFreeText(g2, fm, focused, baseline, boxY, boxH);
            } else if (symbol != Symbol.NONE) {
                g2.setColor(DarculaColors.BLUE_TEXT);
                g2.drawString(symbol.word(), textX(), baseline);
            } else {
                paintSegments(g2, fm, focused, baseline, boxY, boxH);
            }
            g2.setClip(clip);

            if (!freeText) {
                paintArrows(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    private Color outlineColor(boolean focused) {
        if (invalid || freeTextInvalid) {
            return DarculaColors.RED;
        }
        if (focused) {
            return DarculaColors.G7;
        }
        return hoverZone != null ? DarculaColors.G6 : DarculaColors.G5;
    }

    private void paintSegments(Graphics2D g2, FontMetrics fm, boolean focused, int baseline, int boxY, int boxH) {
        int cw = charWidth();
        String shown = DateTimeSegments.format(state.value());
        Segment focusSeg = state.segment();
        for (Segment s : Segment.values()) {
            int x = textX() + s.start() * cw;
            boolean isFocus = focused && s == focusSeg;
            if (isFocus || s == hoverSegment) {
                g2.setColor(isFocus ? DarculaColors.BLUE : DarculaColors.G2);
                g2.fill(new RoundRectangle2D.Float(x - SEG_PAD, boxY + 3, s.digits() * cw + 2 * SEG_PAD, boxH - 6,
                        SEG_ARC, SEG_ARC));
            }
            String digits = shown.substring(s.start(), s.end());
            if (isFocus && state.isTyping()) {
                String typed = state.buffer();
                g2.setColor(DarculaColors.FOREGROUND);
                g2.drawString(typed, x, baseline);
                g2.setColor(DarculaColors.G7);
                g2.drawString(digits.substring(typed.length()), x + typed.length() * cw, baseline);
            } else {
                g2.setColor(isFocus ? DarculaColors.FOREGROUND : DarculaColors.G8);
                g2.drawString(digits, x, baseline);
            }
            if (s != Segment.SECOND) {
                g2.setColor(DarculaColors.G6);
                g2.drawString(shown.substring(s.end(), s.end() + 1), textX() + s.end() * cw, baseline);
            }
        }
    }

    private void paintFreeText(Graphics2D g2, FontMetrics fm, boolean focused, int baseline, int boxY, int boxH) {
        String s = text.toString();
        int avail = getWidth() - 2 * textX();
        int caretX = fm.stringWidth(s.substring(0, caret));
        int scroll = Math.max(0, caretX - avail);
        int x = textX() - scroll;
        g2.setColor(DarculaColors.FOREGROUND);
        g2.drawString(s, x, baseline);
        if (focused) {
            g2.setColor(DarculaColors.CARET);
            g2.fillRect(x + caretX, boxY + PAD_Y, 1, boxH - 2 * PAD_Y);
        }
    }

    private void paintArrows(Graphics2D g2) {
        int cw = charWidth();
        int boxBottom = boxY() + boxHeight();
        for (Segment s : Segment.values()) {
            float cx = textX() + (s.start() + s.end()) * cw / 2f;
            g2.setColor(arrowColor(s, Zone.UP));
            g2.fill(triangle(cx, (ARROW_ZONE - ARROW_H) / 2f, true));
            g2.setColor(arrowColor(s, Zone.DOWN));
            g2.fill(triangle(cx, boxBottom + (ARROW_ZONE - ARROW_H) / 2f, false));
        }
    }

    private Color arrowColor(Segment s, Zone z) {
        if (s == pressedSegment && z == pressedZone) {
            return DarculaColors.BLUE_TEXT;
        }
        if (s == hoverSegment && z == hoverZone) {
            return DarculaColors.G9;
        }
        return s == hoverSegment ? DarculaColors.G7 : DarculaColors.G6;
    }

    private static Shape triangle(float cx, float top, boolean up) {
        Path2D.Float p = new Path2D.Float();
        float half = ARROW_W / 2f;
        if (up) {
            p.moveTo(cx - half, top + ARROW_H);
            p.lineTo(cx + half, top + ARROW_H);
            p.lineTo(cx, top);
        } else {
            p.moveTo(cx - half, top);
            p.lineTo(cx + half, top);
            p.lineTo(cx, top + ARROW_H);
        }
        p.closePath();
        return p;
    }
}
