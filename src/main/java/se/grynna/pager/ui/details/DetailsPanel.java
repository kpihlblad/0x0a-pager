package se.grynna.pager.ui.details;

import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.details.EventDetails.Field;
import se.grynna.pager.ui.details.EventDetails.ScopeMark;
import se.grynna.pager.ui.details.EventDetails.Section;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;
import se.grynna.pager.ui.sprattel.Blockies;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static se.grynna.pager.utils.DarculaColors.*;

/**
 * The inline event details: a header, the message and the fields of one {@link EventDetails}, painted in the
 * log's font and line height over a faint blue tint with a blue edge stripe. A field cursor selects one field at a
 * time; the panel scrolls itself and keeps the selected field visible. Keys, the context menu and copying are left to
 * the owner, which reads {@link #selectedField()} and listens for changes.
 * <p>
 * The wrapped layout is computed when the details or the width change, never per paint. EDT only.
 */
public class DetailsPanel extends JComponent {

    /** Hint shown while there is no event. */
    static final String NO_EVENT = "Place the cursor on a line to see its details";

    private static final int STRIPE_WIDTH = 3;
    private static final int LEFT_PAD = 8;
    private static final int RIGHT_PAD = 10;
    private static final int TOP_PAD = 6;
    private static final int GUTTER_CHARS = 3;
    private static final int KEY_GAP_CHARS = 2;
    private static final int MAX_KEY_CHARS = 40;
    private static final int MIN_COLUMNS = 8;
    private static final int WHEEL_LINES = 3;
    private static final int BADGE_PAD = 6;
    private static final int BADGE_ARC = 6;
    private static final int HEADER_GAP = 12;
    private static final int RULE_GAP = 8;
    private static final int BLOCKIES_GRID_SIZE = 5;
    private static final String EMPTY_VALUE = "—";

    private enum Kind {
        HEADER, ACTIVITY, TIMES, SPACER, RULE, MESSAGE, FIELD
    }

    /** One painted line: {@code field} is the index of the field it belongs to, else -1. */
    private record VisualLine(Kind kind, int field, boolean first, String text, String aux) {}

    private final Font font = Fontalizer.getFont();
    private final Font boldFont = Fontalizer.getBoldFont();
    private final Font ruleFont = font.deriveFont(11f);
    private final Font ruleBoldFont = boldFont.deriveFont(11f);
    private final Font symbolFont = new Font(Font.DIALOG, Font.BOLD, boldFont.getSize());
    private final Blockies blockies = Blockies.defaultCache();

    private EventDetails details;
    private String positionLabel = "";
    private int selected = -1;
    private int scroll;

    private List<VisualLine> lines = List.of();
    private int[] fieldFirstLine = new int[0];
    private int[] fieldLineCount = new int[0];
    private int keyColumns;
    private int layoutWidth = -1;
    private boolean layoutDirty = true;

    private Consumer<Field> selectionListener = f -> {};
    private BiConsumer<Field, Point> fieldMenuHandler = (f, p) -> {};

    public DetailsPanel() {
        setOpaque(true);
        setFocusable(false);
        setFont(font);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleMouse(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleMouse(e);
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                scrollBy(e.getWheelRotation() * WHEEL_LINES);
            }
        };
        addMouseListener(mouse);
        addMouseWheelListener(mouse);
    }

    /**
     * Shows {@code details} and replaces the previous ones. The selected field stays on its name when the new details
     * have it, else on the same index (clamped); the scroll position is kept. The selection listener is told when the
     * selected field changed.
     *
     * @param details       the event, or null for an empty panel
     * @param positionLabel where the event is, e.g. {@code row 12 345}, {@code match 3 of 212}; shown in the header
     */
    public void setDetails(EventDetails details, String positionLabel) {
        Field before = selectedField().orElse(null);
        String name = before == null ? null : before.name();
        int oldIndex = selected;
        this.details = details;
        this.positionLabel = positionLabel == null ? "" : positionLabel;
        layoutDirty = true;
        int count = details == null ? 0 : details.fields().size();
        if (count == 0) {
            selected = -1;
        } else {
            int byName = name == null ? -1 : details.indexOf(name);
            selected = byName >= 0 ? byName : Math.clamp(Math.max(oldIndex, 0), 0, count - 1);
        }
        ensureLayout();
        scroll = Math.clamp(scroll, 0, maxScroll());
        ensureSelectionVisible();
        repaint();
        Field after = selectedField().orElse(null);
        if (after != null && !Objects.equals(before, after)) {
            selectionListener.accept(after);
        }
    }

    /** The details shown, or null. */
    public EventDetails details() {
        return details;
    }

    /** Where the shown event is, as given to {@link #setDetails}; empty when there is none. */
    public String positionLabel() {
        return positionLabel;
    }

    /** The selected field, empty when there are no fields. */
    public Optional<Field> selectedField() {
        return selected >= 0 && details != null ? Optional.of(details.fields().get(selected)) : Optional.empty();
    }

    /** The index of the selected field in {@link EventDetails#fields()}, or -1. */
    public int selectedIndex() {
        return selected;
    }

    /** Selects the field at {@code index} (clamped) and scrolls it into view. */
    public void select(int index) {
        int count = fieldCount();
        if (count == 0) {
            return;
        }
        int target = Math.clamp(index, 0, count - 1);
        boolean changed = target != selected;
        selected = target;
        ensureLayout();
        ensureSelectionVisible();
        repaint();
        if (changed) {
            selectionListener.accept(details.fields().get(selected));
        }
    }

    /** Moves the selection by {@code delta} fields, stopping at the ends. */
    public void moveSelection(int delta) {
        select(Math.max(selected, 0) + delta);
    }

    /** Moves the selection up by a page of visual lines. */
    public void pageUp() {
        page(-1);
    }

    /** Moves the selection down by a page of visual lines. */
    public void pageDown() {
        page(1);
    }

    /** Selects the first field and scrolls to the top, header included. */
    public void home() {
        select(0);
        scroll = 0;
        repaint();
    }

    /** Selects the last field and scrolls to the bottom (to the top of the field when it is taller than the panel). */
    public void end() {
        select(fieldCount() - 1);
        ensureLayout();
        if (selected < 0 || fieldFirstLine[selected] >= maxScroll()) {
            scroll = maxScroll();
        }
        repaint();
    }

    /** Called with the field that became selected (by keys, a click, or new details). */
    public void setSelectionListener(Consumer<Field> listener) {
        this.selectionListener = listener == null ? f -> {} : listener;
    }

    /**
     * Called on a right-click on a field with that field (already selected) and the click point, in this component's
     * coordinates.
     */
    public void setFieldMenuHandler(BiConsumer<Field, Point> handler) {
        this.fieldMenuHandler = handler == null ? (f, p) -> {} : handler;
    }

    /**
     * Where the selected field is painted, in this component's coordinates (the full width, all its visual lines):
     * for placing a menu opened from the keyboard. Empty when nothing is selected.
     */
    public Rectangle selectedFieldBounds() {
        if (selected < 0) {
            return new Rectangle();
        }
        ensureLayout();
        int rowHeight = rowHeight();
        return new Rectangle(0, TOP_PAD + (fieldFirstLine[selected] - scroll) * rowHeight, getWidth(),
                fieldLineCount[selected] * rowHeight);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(100 * charWidth(), 30 * rowHeight());
    }

    int scrollOffset() {
        return scroll;
    }

    private int fieldCount() {
        return details == null ? 0 : details.fields().size();
    }

    // --- metrics ---------------------------------------------------------------------------------------------

    /** The log's line height: ascent plus descent, leading skipped (see LogViewer). */
    private int rowHeight() {
        FontMetrics metrics = getFontMetrics(font);
        return Math.max(1, metrics.getAscent() + metrics.getDescent());
    }

    private int charWidth() {
        return Math.max(1, getFontMetrics(font).charWidth('W'));
    }

    private int textLeft() {
        return STRIPE_WIDTH + LEFT_PAD;
    }

    private int fullLines() {
        return Math.max(1, (getHeight() - TOP_PAD) / rowHeight());
    }

    private int maxScroll() {
        return Math.max(0, lines.size() - fullLines());
    }

    // --- scrolling and selection -------------------------------------------------------------------------------

    private void scrollBy(int delta) {
        ensureLayout();
        scroll = Math.clamp((long) scroll + delta, 0, maxScroll());
        repaint();
    }

    private void page(int direction) {
        int count = fieldCount();
        ensureLayout();
        if (count == 0) {
            scrollBy(direction * fullLines());
            return;
        }
        int current = Math.max(selected, 0);
        int page = fullLines();
        int target = current;
        if (direction > 0) {
            int limit = fieldFirstLine[current] + page;
            while (target + 1 < count && fieldFirstLine[target + 1] <= limit) {
                target++;
            }
            target = Math.max(target, Math.min(current + 1, count - 1));
        } else {
            int limit = fieldFirstLine[current] - page;
            while (target - 1 >= 0 && fieldFirstLine[target] > limit) {
                target--;
            }
            target = Math.min(target, Math.max(current - 1, 0));
        }
        select(target);
    }

    private void ensureSelectionVisible() {
        if (selected < 0 || selected >= fieldFirstLine.length) {
            return;
        }
        int first = fieldFirstLine[selected];
        int last = first + fieldLineCount[selected] - 1;
        int visible = fullLines();
        if (first < scroll) {
            scroll = last < visible ? 0 : first;
        } else if (last >= scroll + visible) {
            scroll = Math.min(first, last - visible + 1);
        }
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    // --- mouse ------------------------------------------------------------------------------------------------

    private void handleMouse(MouseEvent e) {
        boolean popup = e.isPopupTrigger();
        if (!popup && e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        int index = fieldAt(e.getY());
        if (index < 0) {
            return;
        }
        select(index);
        if (popup) {
            fieldMenuHandler.accept(details.fields().get(index), e.getPoint());
        }
    }

    private int fieldAt(int y) {
        if (details == null || y < TOP_PAD) {
            return -1;
        }
        ensureLayout();
        int line = scroll + (y - TOP_PAD) / rowHeight();
        return line < lines.size() ? lines.get(line).field() : -1;
    }

    // --- layout -----------------------------------------------------------------------------------------------

    private void ensureLayout() {
        if (!layoutDirty && layoutWidth == getWidth()) {
            return;
        }
        layoutDirty = false;
        layoutWidth = getWidth();
        List<VisualLine> built = new ArrayList<>();
        if (details == null) {
            lines = built;
            fieldFirstLine = new int[0];
            fieldLineCount = new int[0];
            return;
        }
        int columns = Math.max(MIN_COLUMNS, (layoutWidth - textLeft() - RIGHT_PAD) / charWidth());
        List<Field> fields = details.fields();
        int longestKey = 1;
        for (Field field : fields) {
            longestKey = Math.max(longestKey, field.name().length());
        }
        keyColumns = Math.min(Math.min(MAX_KEY_CHARS, longestKey), Math.max(4, (columns - 2 * GUTTER_CHARS) / 2));
        int valueColumns = Math.max(4, columns - GUTTER_CHARS - keyColumns - KEY_GAP_CHARS);

        built.add(new VisualLine(Kind.HEADER, -1, true, formatTime(details.displayTime()), positionLabel));
        String activity = describe(details.activity());
        if (!activity.isEmpty()) {
            built.add(new VisualLine(Kind.ACTIVITY, -1, true, activity, null));
        }
        String times = times(details);
        if (!times.isEmpty()) {
            built.add(new VisualLine(Kind.TIMES, -1, true, times, null));
        }

        built.add(new VisualLine(Kind.SPACER, -1, true, "", null));
        built.add(new VisualLine(Kind.RULE, -1, true, "MESSAGE", ""));
        List<String> message = wrap(details.message(), columns);
        if (details.message().isEmpty()) {
            message = List.of(EMPTY_VALUE);
        }
        for (String line : message) {
            built.add(new VisualLine(Kind.MESSAGE, -1, true, line, null));
        }

        fieldFirstLine = new int[fields.size()];
        fieldLineCount = new int[fields.size()];
        Section current = null;
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (field.section() != current) {
                current = field.section();
                built.add(new VisualLine(Kind.SPACER, -1, true, "", null));
                int count = details.count(current);
                built.add(new VisualLine(Kind.RULE, -1, true, current.name(),
                        current == Section.RAW ? "" : Integer.toString(count)));
            }
            List<String> value = field.value().isEmpty() ? List.of(EMPTY_VALUE) : wrap(field.value(), valueColumns);
            String key = truncateKey(field.name());
            fieldFirstLine[i] = built.size();
            fieldLineCount[i] = value.size();
            for (int v = 0; v < value.size(); v++) {
                built.add(new VisualLine(Kind.FIELD, i, v == 0, value.get(v), v == 0 ? key : null));
            }
        }
        lines = built;
        scroll = Math.clamp(scroll, 0, maxScroll());
        ensureSelectionVisible();
    }

    private String truncateKey(String name) {
        return name.length() <= keyColumns ? name : name.substring(0, Math.max(0, keyColumns - 1)) + "…";
    }

    /** Splits {@code text} into its lines and wraps each at {@code columns}: at a space when there is one, else hard. */
    static List<String> wrap(String text, int columns) {
        List<String> out = new ArrayList<>();
        int width = Math.max(1, columns);
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1)) {
            String rest = line.stripTrailing();
            if (rest.isEmpty()) {
                out.add("");
                continue;
            }
            while (rest.length() > width) {
                int cut = rest.lastIndexOf(' ', width);
                if (cut <= 0 || rest.substring(0, cut).isBlank()) {
                    out.add(rest.substring(0, width));
                    rest = rest.substring(width);
                } else {
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut + 1);
                }
            }
            out.add(rest);
        }
        return out;
    }

    private static String describe(Activity activity) {
        return activity == null ? "" : activity.describe();
    }

    private static String times(EventDetails details) {
        StringBuilder text = new StringBuilder();
        if (details.timesDiffer() && details.splunkTime() != null) {
            text.append("Splunk ").append(formatTime(details.splunkTime()));
        }
        if (details.indexTime() != null) {
            if (!text.isEmpty()) {
                text.append("   ");
            }
            text.append("indexed ").append(formatTime(details.indexTime()));
        }
        return text.toString();
    }

    private static String formatTime(Instant instant) {
        return instant == null ? EMPTY_VALUE : DateTimeFormatterManager.formatFullLogTimestamp(instant);
    }

    // --- painting ---------------------------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(BACKGROUND_DETAILS);
            g2.fillRect(0, 0, width, height);
            g2.setFont(font);
            FontMetrics metrics = g2.getFontMetrics();
            int rowHeight = Math.max(1, metrics.getAscent() + metrics.getDescent());
            int ascent = metrics.getAscent();
            int charWidth = Math.max(1, metrics.charWidth('W'));
            if (details == null) {
                g2.setColor(COMMENT);
                g2.drawString(NO_EVENT, textLeft(), TOP_PAD + ascent);
            } else {
                ensureLayout();
                paintLines(g2, width, height, rowHeight, ascent, charWidth);
                paintScrollIndicator(g2, width, height, rowHeight);
            }
            g2.setColor(BLUE_TEXT);
            g2.fillRect(0, 0, STRIPE_WIDTH, height);
        } finally {
            g2.dispose();
        }
    }

    private void paintLines(Graphics2D g2, int width, int height, int rowHeight, int ascent, int charWidth) {
        int last = Math.min(lines.size(), scroll + (height - TOP_PAD) / rowHeight + 1);
        for (int i = scroll; i < last; i++) {
            VisualLine line = lines.get(i);
            int y = TOP_PAD + (i - scroll) * rowHeight;
            if (line.kind() == Kind.FIELD && line.field() == selected) {
                g2.setColor(CURRENT_LINE);
                g2.fillRect(STRIPE_WIDTH, y, width - STRIPE_WIDTH, rowHeight);
            }
        }
        for (int i = scroll; i < last; i++) {
            VisualLine line = lines.get(i);
            int y = TOP_PAD + (i - scroll) * rowHeight;
            switch (line.kind()) {
                case HEADER -> paintHeader(g2, line, width, y, rowHeight, ascent);
                case ACTIVITY -> paintActivity(g2, line, y, rowHeight, ascent);
                case TIMES -> {
                    g2.setFont(font);
                    g2.setColor(COMMENT);
                    drawClipped(g2, line.text(), textLeft(), y + ascent, width - RIGHT_PAD);
                }
                case SPACER -> {
                    // blank row
                }
                case RULE -> paintRule(g2, line, width, y, rowHeight, ascent);
                case MESSAGE -> {
                    g2.setFont(font);
                    boolean empty = details.message().isEmpty();
                    g2.setColor(empty ? COMMENT : levelTextColor(details.level()));
                    g2.drawString(line.text(), textLeft(), y + ascent);
                }
                case FIELD -> paintField(g2, line, y, rowHeight, ascent, charWidth);
            }
        }
    }

    private void paintHeader(Graphics2D g2, VisualLine line, int width, int y, int rowHeight, int ascent) {
        String levelName = details.level() == null ? "UNKNOWN" : details.level().name();
        g2.setFont(boldFont);
        FontMetrics bold = g2.getFontMetrics();
        int badgeWidth = bold.stringWidth(levelName) + 2 * BADGE_PAD;
        int x = textLeft();
        g2.setColor(levelBadgeColor(details.level()));
        g2.fill(new RoundRectangle2D.Float(x, y + 1, badgeWidth, rowHeight - 2, BADGE_ARC, BADGE_ARC));
        g2.setColor(BACKGROUND);
        g2.drawString(levelName, x + BADGE_PAD, y + ascent);

        g2.setFont(font);
        FontMetrics metrics = g2.getFontMetrics();
        int right = width - RIGHT_PAD;
        String position = line.aux();
        int positionX = right - metrics.stringWidth(position);
        int timeX = x + badgeWidth + HEADER_GAP;
        boolean timeKnown = details.displayTime() != null;
        boolean showPosition = !position.isEmpty() && positionX - HEADER_GAP >= timeX + 12 * metrics.charWidth('W');
        g2.setColor(timeKnown ? CYAN : COMMENT);
        drawClipped(g2, line.text(), timeX, y + ascent, showPosition ? positionX - HEADER_GAP : right);
        if (showPosition) {
            g2.setColor(COMMENT);
            g2.drawString(position, positionX, y + ascent);
        }
    }

    private void paintActivity(Graphics2D g2, VisualLine line, int y, int rowHeight, int ascent) {
        int x = textLeft();
        int iconSize = Math.max(1, rowHeight - 1);
        int scale = iconSize / BLOCKIES_GRID_SIZE;
        int actual = BLOCKIES_GRID_SIZE * scale;
        Activity activity = details.activity();
        if (scale > 0 && activity != null) {
            String[] seeds = iconSeeds(activity, details.message());
            if (seeds != null) {
                Blockies.Tone tone = details.msgType() == MsgType.JSON
                        ? Blockies.Tone.BRIGHT
                        : Blockies.Tone.DIM;
                g2.drawImage(blockies.icon(seeds[0], seeds[1], seeds[2], tone, BLOCKIES_GRID_SIZE, scale), x,
                        y + (rowHeight - actual) / 2, null);
            }
        }
        g2.setFont(font);
        g2.setColor(FOREGROUND);
        drawClipped(g2, line.text(), x + actual + 6, y + ascent, getWidth() - RIGHT_PAD);
    }

    /** The icon seeds of a row as the log shows them (colour, pattern, spot), or null when there is none. */
    private static String[] iconSeeds(Activity activity, String message) {
        if (activity.patternSeed() != null) {
            String colour = activity.colourSeed() != null ? activity.colourSeed() : activity.patternSeed();
            return new String[]{colour, activity.patternSeed(), activity.spotSeed()};
        }
        if (message != null && !message.isBlank()) {
            return new String[]{message, message, message};
        }
        return null;
    }

    private void paintRule(Graphics2D g2, VisualLine line, int width, int y, int rowHeight, int ascent) {
        int x = textLeft();
        int mid = y + rowHeight / 2;
        g2.setFont(ruleBoldFont);
        FontMetrics bold = g2.getFontMetrics();
        int baseline = mid + (bold.getAscent() - bold.getDescent()) / 2;
        g2.setColor(COMMENT);
        g2.drawString(line.text(), x, baseline);
        int end = x + bold.stringWidth(line.text());
        String count = line.aux();
        if (count != null && !count.isEmpty()) {
            g2.setFont(ruleFont);
            g2.setColor(GREY);
            g2.drawString(count, end + 6, baseline);
            end += 6 + g2.getFontMetrics().stringWidth(count);
        }
        g2.setColor(BLUE);
        g2.drawLine(end + RULE_GAP, mid, width - RIGHT_PAD, mid);
    }

    private void paintField(Graphics2D g2, VisualLine line, int y, int rowHeight, int ascent, int charWidth) {
        Field field = details.fields().get(line.field());
        int x = textLeft();
        int valueX = x + (GUTTER_CHARS + keyColumns + KEY_GAP_CHARS) * charWidth;
        if (line.first()) {
            if (line.field() == selected) {
                paintCaret(g2, x, y, charWidth, rowHeight);
            }
            drawMark(g2, field, x + charWidth, charWidth, y + ascent);
            g2.setFont(boldFont);
            g2.setColor(ORANGE);
            g2.drawString(line.aux(), x + GUTTER_CHARS * charWidth, y + ascent);
        }
        g2.setFont(font);
        g2.setColor(field.value().isEmpty() ? COMMENT : field.section() == Section.RAW ? GREY : FOREGROUND);
        g2.drawString(line.text(), valueX, y + ascent);
    }

    /** A small filled triangle pointing at the selected field's key. */
    private static void paintCaret(Graphics2D g2, int x, int y, int cell, int rowHeight) {
        int h = Math.max(5, rowHeight / 2);
        int w = Math.max(3, h / 2 + 1);
        int left = x + Math.max(0, (cell - w) / 2);
        int top = y + (rowHeight - h) / 2;
        g2.setColor(CARET);
        g2.fillPolygon(new int[]{left, left, left + w}, new int[]{top, top + h, top + h / 2}, 3);
    }

    private void drawMark(Graphics2D g2, Field field, int x, int charWidth, int baseline) {
        String mark;
        Color colour;
        if (field.mark() == ScopeMark.NARROW) {
            mark = "+";
            colour = GREEN;
        } else if (field.mark() == ScopeMark.EXCLUDE) {
            mark = "−";
            colour = RED;
        } else if (field.inMessage()) {
            mark = "✉";
            colour = GREEN;
        } else {
            return;
        }
        g2.setFont(boldFont.canDisplay(mark.charAt(0)) ? boldFont : symbolFont);
        g2.setColor(colour);
        drawCentered(g2, mark, x, charWidth, baseline);
    }

    private static void drawCentered(Graphics2D g2, String text, int x, int cell, int baseline) {
        g2.drawString(text, x + (cell - g2.getFontMetrics().stringWidth(text)) / 2, baseline);
    }

    /** Draws {@code text} from {@code x}, ending with an ellipsis when it would run past {@code right}. */
    private static void drawClipped(Graphics2D g2, String text, int x, int baseline, int right) {
        int available = right - x;
        if (available <= 0) {
            return;
        }
        FontMetrics metrics = g2.getFontMetrics();
        String shown = text;
        if (metrics.stringWidth(text) > available) {
            int chars = Math.max(0, available / Math.max(1, metrics.charWidth('W')) - 1);
            shown = text.substring(0, Math.min(chars, text.length())) + "…";
        }
        g2.drawString(shown, x, baseline);
    }

    private void paintScrollIndicator(Graphics2D g2, int width, int height, int rowHeight) {
        int viewport = height - TOP_PAD;
        int content = lines.size() * rowHeight;
        if (content <= viewport) {
            return;
        }
        int thumb = Math.max(20, viewport * viewport / content);
        int travel = viewport - thumb;
        int max = maxScroll();
        int top = TOP_PAD + (max == 0 ? 0 : (int) ((long) travel * scroll / max));
        g2.setColor(GREY_DARK_TRANSLUCENT);
        g2.fillRoundRect(width - 5, top, 3, thumb, 3, 3);
    }

    private static Color levelTextColor(Level level) {
        if (level == null) {
            return FOREGROUND;
        }
        return switch (level) {
            case ERROR -> RED;
            case WARNING -> ORANGE;
            case INFO -> FOREGROUND;
        };
    }

    private static Color levelBadgeColor(Level level) {
        if (level == null) {
            return COMMENT;
        }
        return switch (level) {
            case ERROR -> RED;
            case WARNING -> ORANGE;
            case INFO -> DarculaColors.GREEN;
        };
    }
}
