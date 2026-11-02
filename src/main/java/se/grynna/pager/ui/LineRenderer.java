package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.*;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;
import se.grynna.pager.ui.sprattel.Blockies;
import se.grynna.pager.ui.sprattel.LoadingAnimationTimer;
import se.grynna.pager.utils.DarculaColors;

import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.text.AttributedString;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import static java.lang.Integer.min;
import static java.lang.Math.max;
import static org.apache.commons.lang3.StringUtils.rightPad;
import static org.apache.commons.lang3.StringUtils.truncate;
import static se.grynna.pager.ui.Cursor.MAXLINE_LENGTH;
import static se.grynna.pager.utils.DarculaColors.*;

@Slf4j
public class LineRenderer {

    private static final int BLOCKIES_GRID_SIZE = 5;
    /** Width of the edge stripe of the Matches (amber) and Bookmarks (teal) views; drawn in the margin, never shifts text. */
    static final int MATCHES_STRIPE_WIDTH = 3;
    private static final int RULE_LABEL_PADDING = 4;
    private static final int RULE_TAG_ARC = 6;
    /** English on purpose: the rule text is locale-independent, like the rest of the Matches view. */
    private static final DateTimeFormatter RULE_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd",
            Locale.ENGLISH);

    /** Debug aid: draw the row number on runs of consecutive boundary lines (off by default). */
    private static final boolean DEBUG_DRAW_BOUNDARY_LINE_NUMBERS = false;

    private final Font font;
    private final LoadingAnimationTimer spinnerator;
    private final FontMetrics fontMetrics;
    private final Rectangle renderArea;
    private final Blockies blockies;

    //    private  int charWidth;

    //    public final int lineHeight; // Height of a line of text
    //    private final int charSize.ascent; // Ascent of the font (distance from baseline to top of text)
    //    private final int fontLeading; // Leading of the font (distance between lines of text)

    private LogViewer.CharacterSize charSize = null;
    /** Which view is painted: the event-list views get a tinted background, an edge stripe and between-event rules. */
    private ViewKind viewKind = ViewKind.LOG;
    /** The last loaded row painted and its rule: rows are painted top-down, so this is the previous row's rule. */
    private DecoratedLogLine lastRuleLine;
    private GapRule lastRule;

    /** The lens the rows are painted for; each event-list view has its own accent colour. */
    public enum ViewKind {
        LOG, MATCHES, BOOKMARKS
    }

    /**
     * Computes the pixel width reserved for the Blockies icon, based on character height.
     * Used by both rendering and click coordinate correction.
     */
    public static int iconSpaceWidth(int charHeight) {
        int iconSize = Math.max(1, charHeight - 1);
        int scale = iconSize / BLOCKIES_GRID_SIZE;
        int actualIconSize = BLOCKIES_GRID_SIZE * scale;
        int iconPadding = 2;
        return actualIconSize + iconPadding;
    }

    /** Seeds of a row's icon: the colour (background), pattern (shape and foreground) and spot seeds. */
    record IconSeeds(String colourSeed, String patternSeed, String spotSeed) {}

    /**
     * The icon seeds for a row: the activity when it is known, else the message; null when neither gives a seed (no
     * icon is drawn).
     */
    static IconSeeds iconSeeds(Activity activity, String message) {
        if (activity != null && activity.patternSeed() != null) {
            String colour = activity.colourSeed() != null ? activity.colourSeed() : activity.patternSeed();
            return new IconSeeds(colour, activity.patternSeed(), activity.spotSeed());
        }
        if (message != null && !message.isBlank()) {
            return new IconSeeds(message, message, message);
        }
        return null;
    }

    public LineRenderer(LoadingAnimationTimer spinnerator, FontMetrics fontMetrics, Rectangle renderArea) {
        this.font = Fontalizer.getFont();
        this.spinnerator = spinnerator;
        this.fontMetrics = fontMetrics;
        this.renderArea = renderArea;
        this.blockies = Blockies.defaultCache();
        //        this.charWidth = fontMetrics.getMaxAdvance() > 0
        //            ? fontMetrics.getMaxAdvance()
        //            : fontMetrics.charWidth('W');
        //        this.lineHeight = fontMetrics.getHeight();
        //        this.charSize.ascent = fontMetrics.getAscent();
        //        this.fontLeading = fontMetrics.getLeading();

    }

    /** Set per paint by the viewer: true while the Matches view is shown. */
    public void setMatchesView(boolean matchesView) {
        setViewKind(matchesView ? ViewKind.MATCHES : ViewKind.LOG);
    }

    /** Set per paint by the viewer: the view whose rows are painted. */
    public void setViewKind(ViewKind viewKind) {
        this.viewKind = viewKind;
    }

    private boolean eventListView() {
        return viewKind != ViewKind.LOG;
    }

    private Color viewTint() {
        return viewKind == ViewKind.BOOKMARKS ? BACKGROUND_BOOKMARKS : BACKGROUND_MATCHES;
    }

    private Color viewAccent() {
        return viewKind == ViewKind.BOOKMARKS ? BOOKMARK : MATCHES_ACCENT;
    }

    private Color viewAccentDim() {
        return viewKind == ViewKind.BOOKMARKS ? BOOKMARK_DIM : MATCHES_ACCENT_DIM;
    }

    /** Fills the whole viewport with the view's tint; no-op in the log view. */
    public void paintBackground(Graphics2D g2d, int width, int height) {
        if (eventListView()) {
            g2d.setColor(viewTint());
            g2d.fillRect(0, 0, width, height);
        }
    }

    public void render(Graphics2D g2d, DecoratedLogLine line, DecoratedLogLine nextLine, DecoratedLogLine prevLine,
            int horizontalScroll, int y) {

        g2d.setFont(font);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (line == null) {
            return;
        }

        switch (line.getLogLine()) {
            case Placeholder p ->
                doPlaceholder(g2d, renderArea.x, y, fontMetrics.getHeight(), renderArea.width, p.line(), p.message());

            case BeforeBoundaryLine before -> {
                g2d.setColor(BACKGROUND_OUTSIDE);
                g2d.fillRect(renderArea.x, y, renderArea.width, charSize.height());

                if (before.message() != null && before.line() == before.boundary().line().orElse(0) - 1) {
                    g2d.setColor(COMMENT);
                    g2d.drawString(before.message(), renderArea.x + 10, y + charSize.ascent());
                } else if (before.message() == null && nextLine != null
                        && !(nextLine.getLogLine() instanceof BeforeBoundaryLine)) {

                    // draw a thin line to indicate boundary
                    g2d.setColor(DarculaColors.YELLOW);
                    g2d.drawLine(renderArea.x, y + charSize.height() - 1, renderArea.x + renderArea.width,
                            y + charSize.height() - 1);
                    g2d.drawString(
                            "▲Before search range "
                                    + DateTimeFormatterManager.formatTimestamp(before.boundary().instant()),
                            renderArea.x + 10, y + charSize.ascent());

                } else if (DEBUG_DRAW_BOUNDARY_LINE_NUMBERS && log.isDebugEnabled()) {
                    g2d.setColor(COMMENT); // Darcula current line
                    g2d.drawString(Integer.toString(line.logLine.line()), renderArea.x, y + charSize.ascent());
                }
            }
            case AfterBoundaryLine after -> {
                g2d.setColor(BACKGROUND_OUTSIDE);
                g2d.fillRect(renderArea.x, y, renderArea.width, charSize.height());

                if (after.message() != null && after.line() == after.boundary().line().orElse(0)) {
                    g2d.setColor(COMMENT);
                    g2d.drawString(after.message(), renderArea.x + 10, y + charSize.ascent());
                } else if (after.message() == null && prevLine != null
                        && !(prevLine.getLogLine() instanceof AfterBoundaryLine)) {

                    g2d.setColor(YELLOW);
                    g2d.drawLine(renderArea.x, y, renderArea.x + renderArea.width, y);
                    var s = "After search range " + DateTimeFormatterManager.formatTimestamp(after.boundary().instant())
                            + "▼";
                    var rAlign = renderArea.width - fontMetrics.stringWidth(s);
                    g2d.drawString(s, renderArea.x + rAlign, y + charSize.ascent());

                } else if (DEBUG_DRAW_BOUNDARY_LINE_NUMBERS && log.isDebugEnabled()) {
                    // else {
                    g2d.setColor(COMMENT); // Darcula current line   {
                    g2d.drawString("" + line.logLine.line() + after.boundary(), renderArea.x, y + charSize.ascent());
                }
            }

            case NowBoundaryLine now -> {
                g2d.setColor(BACKGROUND_WARNING);
                g2d.fillRect(renderArea.x, y, renderArea.width, charSize.height());

                if (prevLine != null && !(prevLine.getLogLine() instanceof NowBoundaryLine)) {

                    // draw a thin line to indicate boundary
                    g2d.setColor(RED);
                    g2d.drawLine(renderArea.x, y, renderArea.x + renderArea.width, y);
                    var s = "Later than " + DateTimeFormatterManager.formatTimestamp(now.boundary().instant()) + "▼";
                    var rAlign = renderArea.width - fontMetrics.stringWidth(s);
                    g2d.drawString(s, renderArea.x + rAlign, y + charSize.ascent());

                } else {
                    if (DEBUG_DRAW_BOUNDARY_LINE_NUMBERS && log.isDebugEnabled()) {
                        g2d.setColor(COMMENT); // Darcula current line
                        g2d.drawString(Integer.toString(line.logLine.line()), renderArea.x, y + charSize.ascent());
                    }
                }
            }
            case LoadedLogLine ignored -> {
                g2d.setColor(FOREGROUND);

                LoadedLogLine loadedLine = (LoadedLogLine) line.getLogLine();

                // Calculate icon size to fit line height (typically 15-16px for lineHeight ~16)
                int iconSize = Math.max(1, charSize.height() - 1);
                int scale = iconSize / BLOCKIES_GRID_SIZE;
                int actualIconSize = BLOCKIES_GRID_SIZE * scale;

                int iconSpaceWidth = iconSpaceWidth(charSize.height());
                int textStartX = renderArea.x + iconSpaceWidth;

                // calculate

                var areaLength = 1 + (renderArea.width - iconSpaceWidth) / charSize.width();
                var text = rightPad(truncate(line.formattedLine(), horizontalScroll, MAXLINE_LENGTH), areaLength);
                var preambleEnd = max(0, line.getPreambleEnd() - horizontalScroll);

                var display = new AttributedString(text);
                display.addAttribute(TextAttribute.FONT, font);

                // A bookmarked row gets the bookmark fill under everything else; the cursor line on it is a blend.
                boolean bookmarkRow = line.isBookmarked() && viewKind != ViewKind.BOOKMARKS;
                Color cursorColor = bookmarkRow ? BOOKMARK_CURRENT_LINE : CURRENT_LINE;

                // set background for cursor line
                if (line.isHighlighted()) {
                    display.addAttribute(TextAttribute.BACKGROUND, cursorColor, 0, text.length());
                }

                if (preambleEnd > 0) {
                    display.addAttribute(TextAttribute.FOREGROUND, DarculaColors.GREY, 0, preambleEnd);
                }

                // Set text color based on log level (ERROR=red, WARNING=orange, INFO=default)
                if (text.length() > preambleEnd) {
                    Color textColor = switch (loadedLine.getLevel()) {
                        case ERROR -> RED;
                        case WARNING -> ORANGE;
                        case INFO -> FOREGROUND;
                        case null -> FOREGROUND;
                    };
                    display.addAttribute(TextAttribute.FOREGROUND, textColor, preambleEnd, text.length());
                }

                // Apply inline message style spans (welcome/help screen colours and bold) before highlights and selection.
                applyStyleSpans(display, text.length(), line.getPreambleEnd(), horizontalScroll, line.getStyleSpans());

                // Apply text highlights (selection echo, search matches, etc.)
                // Applied before primary selection so that the selection colour takes precedence.
                applyTextHighlights(display, text, line.getHighlights());

                // Primary selection — can come at any position
                int selectionFrom = -1;
                int selectionTo = -1;
                if (line.selectionStart >= 0 && line.selectionStart != line.selectionEnd) {
                    var selectionStart = max(0, line.selectionStart - horizontalScroll);
                    var selectionEnd = max(0, min(text.length(), line.selectionEnd - horizontalScroll));
                    if (selectionStart < selectionEnd) {
                        display.addAttribute(TextAttribute.BACKGROUND, MIDNIGHT_BLUE, selectionStart, selectionEnd);
                        selectionFrom = selectionStart;
                        selectionTo = selectionEnd;
                    }
                }

                // Whole-pixel row fills under the text: the antialiased text background left a dark seam at the
                // top of selected rows, most visible when the selection grows upwards. Same colours as the attributes.
                // The bookmark fill spans the whole row, margin and icon included; selection and cursor paint over it.
                int rowRight = renderArea.x + renderArea.width;
                if (bookmarkRow) {
                    fillRowSpan(g2d, renderArea.x, rowRight, y, BOOKMARK_ROW);
                }
                if (line.isHighlighted()) {
                    fillRowSpan(g2d, textStartX, rowRight, y, cursorColor);
                }
                if (selectionFrom >= 0) {
                    fillRowSpan(g2d, textStartX + charSize.width() * selectionFrom,
                            min(rowRight, textStartX + charSize.width() * selectionTo), y, MIDNIGHT_BLUE);
                }

                // Render Blockies icon at leftmost position
                IconSeeds seeds = iconSeeds(loadedLine.resolvedActivity(), loadedLine.getMessage());
                if (seeds != null) {
                    Blockies.Tone tone = loadedLine.getMsgType() == MsgType.JSON
                            ? Blockies.Tone.BRIGHT
                            : Blockies.Tone.DIM;
                    int iconX = renderArea.x;
                    int iconY = y + (charSize.height() - actualIconSize) / 2; // Center vertically
                    g2d.drawImage(blockies.icon(seeds.colourSeed(), seeds.patternSeed(), seeds.spotSeed(), tone,
                            BLOCKIES_GRID_SIZE, scale), iconX, iconY, null);
                }

                // The margin marks are drawn after the icon, which fills the margin: they stay visible over it.
                if (eventListView()) {
                    g2d.setColor(viewAccent());
                    g2d.fillRect(renderArea.x, y, MATCHES_STRIPE_WIDTH, charSize.height());
                }

                g2d.drawString(display.getIterator(), textStartX, y + charSize.ascent());

                // paint caret
                var caretX = (line.getCursorPosition() - horizontalScroll);

                if (caretX >= 0) {
                    g2d.setColor(YELLOW);
                    int paintWidth = 2;
                    g2d.fillRect(textStartX + charSize.width() * caretX, y, paintWidth, charSize.height());
                }

                if (line.getSuffix() != null) {
                    drawSuffix(g2d, line, textStartX, horizontalScroll, y);
                }
                GapRule rule = ruleBefore(line, prevLine);
                if (rule != null) {
                    // Date rules on adjacent rows (one row per day) are drawn thin so the run does not look heavy.
                    boolean denseDates = rule instanceof GapRule.Date
                            && (isDate(lastRuleLine == prevLine && prevLine != null ? lastRule : null)
                                    || isDate(nextLine == null ? null : ruleBefore(nextLine, line)));
                    drawRule(g2d, rule, y, textEndX(line, textStartX, horizontalScroll),
                            prevLine == null ? null : textEndX(prevLine, textStartX, horizontalScroll), denseDates);
                }
                lastRuleLine = line;
                lastRule = rule;
            }
            case null -> log.warn("null in render");
            default -> {
                log.warn("unknown type in render: {}", line.getLogLine());
            }
        }
    }

    /** Paints a full row-height span of {@code color} from {@code x0} to {@code x1} (no-op when empty). */
    private void fillRowSpan(Graphics2D g2d, int x0, int x1, int y, Color color) {
        if (x1 <= x0) {
            return;
        }
        g2d.setColor(color);
        g2d.fillRect(x0, y, x1 - x0, charSize.height());
    }

    /** The rule at the top of {@code line}: the provided rule in the event-list views, else a log view date rule. */
    private GapRule ruleBefore(DecoratedLogLine line, DecoratedLogLine prevLine) {
        return eventListView() ? line.getGapRule() : logViewDateRule(line, prevLine);
    }

    private static boolean isDate(GapRule rule) {
        return rule instanceof GapRule.Date;
    }

    /**
     * Log view date turnover: a date rule where the local day changes between two consecutive loaded rows
     * (dates only, no gap rules). Continuation rows and rows next to placeholders or boundaries get none;
     * the first visible row has no painted previous row and gets none either.
     */
    private static GapRule logViewDateRule(DecoratedLogLine line, DecoratedLogLine prevLine) {
        if (prevLine == null || !(line.getLogLine() instanceof LoadedLogLine next) || next.isContinuation()
                || !(prevLine.getLogLine() instanceof LoadedLogLine prev)) {
            return null;
        }
        return GapRule.between(prev.getDisplayTime(), next.getDisplayTime(), Optional.empty(),
                DateTimeFormatterManager.zoneId());
    }

    /** Dim suffix right after the message; drawn only, so copy and selection text never contain it. */
    private void drawSuffix(Graphics2D g2d, DecoratedLogLine line, int textStartX, int horizontalScroll, int y) {
        int messageEnd = line.formattedLine().stripTrailing().length();
        int column = max(messageEnd, line.getPreambleEnd()) + 1 - horizontalScroll;
        int x = textStartX + charSize.width() * column;
        if (column < 0 || x >= renderArea.x + renderArea.width) {
            return;
        }
        g2d.setColor(GREY);
        g2d.drawString(line.getSuffix(), x, y + charSize.ascent());
    }

    /** Where a row's drawn text (message plus suffix) ends, in pixels. */
    private int textEndX(DecoratedLogLine line, int textStartX, int horizontalScroll) {
        String text = line.formattedLine();
        int columns = (text == null ? 0 : text.length())
                + (line.getSuffix() != null ? line.getSuffix().length() + 1 : 0);
        return textStartX + charSize.width() * Math.max(0, columns - horizontalScroll);
    }

    /**
     * Rule at the top edge of an event's first row, between it and the previous event. The row
     * keeps its height: the line sits on the edge; the right-aligned label goes below it (on this
     * row) when this row's text leaves room, else above it (on the previous row, drawn before this
     * one) when that row's text leaves room, else it is left out. Text is never hidden.
     */
    private void drawRule(Graphics2D g2d, GapRule rule, int y, int textEndX, Integer prevTextEndX,
            boolean denseDates) {
        switch (rule) {
            case GapRule.Gap(var duration) -> {
                g2d.setColor(viewAccentDim());
                g2d.drawLine(renderArea.x, y, renderArea.x + renderArea.width, y);
                drawRuleLabel(g2d, DurationText.shortText(duration) + " later", COMMENT, viewTint(), y, 1,
                        textEndX, prevTextEndX, false);
            }
            case GapRule.Date(var date) -> {
                int thickness;
                if (denseDates) {
                    Color accent = viewAccent();
                    g2d.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 128));
                    g2d.drawLine(renderArea.x, y, renderArea.x + renderArea.width, y);
                    thickness = 1;
                } else {
                    g2d.setColor(viewAccent());
                    g2d.fillRect(renderArea.x, y, renderArea.width, 2);
                    thickness = 2;
                }
                drawRuleLabel(g2d, RULE_DATE_FORMAT.format(date), BACKGROUND, viewAccent(), y, thickness, textEndX,
                        prevTextEndX, true);
            }
            case GapRule.None none -> {
                // nothing between these events
            }
        }
    }

    /**
     * Draws a rule label clear of the line: as a tag just under the line, else just above it when the
     * previous row leaves room, else not at all. The first painted row has no previous row, so its
     * label is left out when this row's text reaches it (the status bar shows the first row's time).
     */
    private void drawRuleLabel(Graphics2D g2d, String text, Color textColor, Color fill, int y, int ruleThickness,
            int textEndX, Integer prevTextEndX, boolean dateTag) {
        int width = fontMetrics.stringWidth(text);
        int x = renderArea.x + renderArea.width - width - RULE_LABEL_PADDING;
        int tagHeight = charSize.height() - 2;
        int tagTop;
        if (labelFits(x, textEndX)) {
            tagTop = y + ruleThickness;
        } else if (prevTextEndX != null && labelFits(x, prevTextEndX) && y >= charSize.height()) {
            tagTop = y - tagHeight;
        } else {
            return;
        }
        int tagLeft = x - RULE_LABEL_PADDING;
        int tagWidth = width + 2 * RULE_LABEL_PADDING;
        g2d.setColor(fill);
        if (dateTag) {
            g2d.fillRoundRect(tagLeft, tagTop, tagWidth, tagHeight, RULE_TAG_ARC, RULE_TAG_ARC);
        } else {
            g2d.fillRect(tagLeft, tagTop, tagWidth, tagHeight);
        }
        int baseline = tagTop + (tagHeight - fontMetrics.getHeight()) / 2 + fontMetrics.getAscent();
        g2d.setColor(textColor);
        g2d.drawString(text, x, baseline);
    }

    static boolean labelFits(int labelX, int textEndX) {
        return labelX - 2 * RULE_LABEL_PADDING >= textEndX;
    }

    /**
     * Applies precomputed foreground and bold spans (in 0-based message coordinates) onto the
     * horizontally scrolled display text without allocating in the paint loop.
     */
    private void applyStyleSpans(AttributedString display, int textLength, int preambleEnd, int horizontalScroll,
            java.util.List<StyleSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        int msgOffset = preambleEnd - horizontalScroll;
        int minIdx = max(0, msgOffset);
        Font boldFont = Fontalizer.getBoldFont();
        for (int i = 0, n = spans.size(); i < n; i++) {
            StyleSpan span = spans.get(i);
            int from = max(minIdx, msgOffset + span.start());
            int to = min(textLength, msgOffset + span.end());
            if (from < to) {
                if (span.bold()) {
                    display.addAttribute(TextAttribute.FONT, boldFont, from, to);
                }
                if (span.foreground() != null) {
                    Color color = span.foreground().get();
                    if (color != null) {
                        display.addAttribute(TextAttribute.FOREGROUND, color, from, to);
                    }
                }
            }
        }
    }

    /**
     * Finds all occurrences of each highlight pattern in the displayed text
     * and applies background (and optional foreground) attributes.
     */
    private void applyTextHighlights(AttributedString display, String text, java.util.List<TextHighlight> highlights) {
        if (highlights == null || highlights.isEmpty()) return;

        for (TextHighlight hl : highlights) {
            String pattern = hl.pattern();
            if (pattern == null || pattern.isEmpty()) continue;

            // Case-insensitive highlights match against a folded copy; indices stay aligned
            // because toLowerCase is applied per-string and lengths are preserved for the
            // characters that occur in log output.
            String haystack = hl.caseSensitive() ? text : text.toLowerCase(Locale.ROOT);
            String needle = hl.caseSensitive() ? pattern : pattern.toLowerCase(Locale.ROOT);
            if (haystack.length() != text.length() || needle.isEmpty()) continue;

            int idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) >= 0) {
                int end = idx + needle.length();
                display.addAttribute(TextAttribute.BACKGROUND, hl.background(), idx, end);
                if (hl.foreground() != null) {
                    display.addAttribute(TextAttribute.FOREGROUND, hl.foreground(), idx, end);
                }
                idx = end;
            }
        }
    }

    private void doPlaceholder(Graphics2D g2d, int x, int y, int lineHeight, int width, int lineNo, String message) {

        // Colors from FlatDarculaLaf
        int h = lineHeight / 2; // 50% of line height
        int yOffset = (lineHeight - h) / 2; // Center vertically

        // Define gradient based on animation fraction 'f'
        float[] fractions;
        Color[] colors;

        float f = spinnerator.getF();
        var placeholderWidth = placeholderWidth(lineNo, width); // pseudorandom width fraction

        // Gradient sweep coordinates relative to rendering position
        float gradientStart = x - width + f * width * 2f;
        float gradientEnd = x + f * width * 2f;

        fractions = new float[]{0f, 0.25f, 0.5f, 0.75f, 1f};
        colors = new Color[]{BACKGROUND_PLUS, BACKGROUND_PLUS, CURRENT_LINE, BACKGROUND_PLUS, BACKGROUND_PLUS};
        // Create horizontal gradient for the computed width
        LinearGradientPaint gradient = new LinearGradientPaint(gradientStart, y, gradientEnd, y + h, fractions, colors,
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2d.setColor(eventListView() ? viewTint() : BACKGROUND);
        g2d.fillRect(x, y, width, lineHeight);

        if (message.length() > 0) {
            // Draw message text with gradient fill
            Color[] textColors = new Color[]{COMMENT, COMMENT, FOREGROUND, COMMENT, COMMENT};
            LinearGradientPaint textGradient = new LinearGradientPaint(gradientStart, y, gradientEnd, y + h, fractions,
                    textColors, MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g2d.setPaint(textGradient);
            g2d.drawString(message, x + 10, y + charSize.ascent());
        } else {
            // Subtle pulsating rectangle placeholder
            g2d.setPaint(gradient);
            g2d.fill(new Rectangle2D.Double(x, y + yOffset, placeholderWidth, h));
        }
    }

    // Pseudorandom function to compute width fraction based on index
    private int placeholderWidth(int index, int width) {

        // modulo random width
        float percentage = randomWidths[Math.abs(index) % randomWidths.length];

        int randomWidth = (int) (percentage * width); // Scale to width
        return randomWidth;
    }

    // randomly generate 200 values between 0.33 and 0.66
    static final float[] randomWidths = randomWidths(1000);

    private static float[] randomWidths(int size) {
        float[] randomWidths = new float[size];
        for (int i = 0; i < size; i++) {
            randomWidths[i] = 0.4f + (float) Math.random() * 0.33f;
        }
        return randomWidths;
    }

    public void setCharSize(LogViewer.CharacterSize charSize) {
        this.charSize = charSize;
    }
}

// Bef than search boundary
//
