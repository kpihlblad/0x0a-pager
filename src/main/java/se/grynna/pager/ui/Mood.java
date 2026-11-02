package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.*;
import se.grynna.pager.ui.stars.ASCIINightSkyGenerator;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.SbomTransformer;

import java.awt.Color;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static se.grynna.pager.splunk.BoundaryType.*;
import static se.grynna.pager.splunk.Level.INFO;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.RAW;

@Slf4j
public class Mood implements LogDataProvider {

    /** Default instruction row shown on the welcome screen before login. */
    public static final String LOGIN_INSTRUCTION = "Press L to login and select index.";

    private static final Supplier<Color> FG = () -> DarculaColors.FOREGROUND;
    private static final Supplier<Color> CYAN = () -> DarculaColors.CYAN;
    private static final Supplier<Color> ORANGE = () -> DarculaColors.ORANGE;
    private static final Supplier<Color> YELLOW = () -> DarculaColors.YELLOW;
    private static final Supplier<Color> BLUE = () -> DarculaColors.BLUE_TEXT;
    private static final Supplier<Color> PURPLE = () -> DarculaColors.PURPLE;
    private static final Supplier<Color> PINK = () -> DarculaColors.PINK;
    private static final Supplier<Color> COMMENT = () -> DarculaColors.COMMENT;

    private static final int DEFAULT_SKY_WIDTH = 200;
    private static final int MIN_SKY_WIDTH = 80;
    private static final int SKY_CHUNK_HEIGHT = 32;
    private static final int MAX_CACHED_SKY_CHUNKS = 64;

    private static final Set<UiAction> DEFAULT_BOUND_ACTIONS = Collections.unmodifiable(
            EnumSet.complementOf(EnumSet.of(UiAction.SEARCH_SELECTION_FORWARD, UiAction.SEARCH_SELECTION_BACKWARD)));

    private static final class Collections {
        static Set<UiAction> unmodifiable(Set<UiAction> set) {
            return java.util.Collections.unmodifiableSet(set);
        }
    }

    /** A line of plain text with its precomputed style spans (zero allocation during rendering). */
    record StyledLine(String text, List<StyleSpan> spans) {
        static final StyledLine EMPTY = new StyledLine("", List.of());
    }

    private static final StyledLine[] LICENCE_LINES = buildLicenceLines();

    volatile int i = (int) (Math.random() * moods.length);
    private final Instant now;
    private final long skySeed = ThreadLocalRandom.current().nextLong();
    private int skyWidth = DEFAULT_SKY_WIDTH;
    private final Map<Integer, StyledLine[]> skyChunks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, StyledLine[]> eldest) {
            return size() > MAX_CACHED_SKY_CHUNKS;
        }
    };
    private final Supplier<String> loginInstruction;

    private KeyMap keyMap;
    private List<UiAction> actions;
    private List<KeyFlavour> columns;
    private StyledLine[] signLines;
    private int loginInstructionLine;
    private String cachedInstructionText;

    private final Boundary beforeBoundary = new Boundary(BEFORE, randomInstant());
    private Boundary afterBoundary;
    private Boundary nowBoundary;

    public Mood() {
        this(Clock.systemUTC());
    }

    public Mood(Clock clock) {
        this(clock, KeyMap.defaults(), DEFAULT_BOUND_ACTIONS, () -> LOGIN_INSTRUCTION);
    }

    public Mood(Clock clock, Supplier<String> loginInstruction) {
        this(clock, KeyMap.preferred(), DEFAULT_BOUND_ACTIONS, loginInstruction);
    }

    public Mood(KeyMap keyMap, Set<UiAction> boundActions) {
        this(Clock.systemUTC(), keyMap, boundActions, () -> loginInstruction(keyMap));
    }

    public Mood(Clock clock, KeyMap keyMap, Set<UiAction> boundActions, Supplier<String> loginInstruction) {
        this.now = clock.instant();
        this.loginInstruction = Objects.requireNonNull(loginInstruction, "loginInstruction");
        rebuild(keyMap, boundActions);
    }

    /** Welcome-screen instruction for the given key map. */
    public static String loginInstruction(KeyMap keyMap) {
        String key = keyMap.label(UiAction.LOGIN);
        return "L".equals(key) ? LOGIN_INSTRUCTION : "Press " + key + " to login and select index.";
    }

    /** Help-screen instruction when already logged in: scope change and how to return to the log. */
    public static String loggedInInstruction(KeyMap keyMap) {
        return "Press " + keyMap.label(UiAction.LOGIN) + " to change scope (index and filters), or "
                + keyMap.label(UiAction.HELP) + " / " + keyMap.label(UiAction.CANCEL) + " to return to the log.";
    }

    /**
     * Updates the horizontal width of generated night-sky lines so they fill the visible viewport width.
     * Clears cached sky chunks when the width changes.
     */
    public synchronized boolean setSkyWidth(int width) {
        int clamped = Math.max(MIN_SKY_WIDTH, width);
        if (this.skyWidth != clamped) {
            this.skyWidth = clamped;
            this.skyChunks.clear();
            return true;
        }
        return false;
    }

    public synchronized int getSkyWidth() {
        return skyWidth;
    }

    /** Rebuilds the help table when the preferred key flavour or bound actions change. */
    public synchronized void refresh(KeyMap newKeyMap, Set<UiAction> boundActions) {
        List<UiAction> ordered = Arrays.stream(UiAction.values()).filter(boundActions::contains).toList();
        if (this.keyMap != null && this.keyMap.flavour() == newKeyMap.flavour() && ordered.equals(this.actions)) {
            syncLoginInstruction();
            return;
        }
        rebuild(newKeyMap, boundActions);
    }

    private synchronized void rebuild(KeyMap newKeyMap, Set<UiAction> boundActions) {
        this.keyMap = newKeyMap;
        this.actions = Arrays.stream(UiAction.values()).filter(boundActions::contains).toList();
        this.columns = columnOrder(newKeyMap.flavour());

        List<StyledLine> built = new ArrayList<>();
        String logo = moods[i % moods.length];
        for (String line : logo.lines().toList()) {
            built.add(styleLogoLine(line));
        }

        built.add(StyledLine.EMPTY);
        built.add(parseMarkup("# 0x0A PAGER — Splunk log browser"));
        built.add(StyledLine.EMPTY);

        this.loginInstructionLine = built.size();
        String instruction = currentInstructionText();
        this.cachedInstructionText = instruction;
        built.add(styleInstructionLine(instruction));

        for (String line : overviewLines.lines().toList()) {
            built.add(parseMarkup(line));
        }

        appendKeyboardTable(built);
        built.add(StyledLine.EMPTY);

        this.signLines = built.toArray(StyledLine[]::new);
        int totalLines = this.signLines.length + LICENCE_LINES.length;
        this.afterBoundary = new Boundary(AFTER, now, totalLines);
        this.nowBoundary = new Boundary(NOW, now.plusSeconds(3600), 1000 + totalLines);
    }

    private synchronized void syncLoginInstruction() {
        String current = currentInstructionText();
        if (!Objects.equals(current, cachedInstructionText) && signLines != null
                && loginInstructionLine >= 0 && loginInstructionLine < signLines.length) {
            cachedInstructionText = current;
            signLines[loginInstructionLine] = styleInstructionLine(current);
        }
    }

    private String currentInstructionText() {
        String text = loginInstruction.get();
        return text == null || text.isBlank() ? LOGIN_INSTRUCTION : text;
    }

    /** The bound actions in declaration order — exactly what the keyboard table lists. */
    List<UiAction> actions() {
        return actions;
    }

    /** The flavour columns left to right: the preferred one first, then the others in declaration order. */
    List<KeyFlavour> columns() {
        return columns;
    }

    private static List<KeyFlavour> columnOrder(KeyFlavour preferred) {
        List<KeyFlavour> order = new ArrayList<>();
        order.add(preferred);
        for (KeyFlavour flavour : KeyFlavour.values()) {
            if (flavour != preferred) {
                order.add(flavour);
            }
        }
        return List.copyOf(order);
    }

    /** True when {@code action} has a key of exactly {@code flavour}; false means the cell shows the fallback. */
    boolean hasKey(UiAction action, KeyFlavour flavour) {
        return keyMap.hasFlavour(action, flavour);
    }

    /** One cell of the table, e.g. {@code "F3"} or {@code "n"}: the flavour's keys, else the fallback key. */
    String cell(UiAction action, KeyFlavour flavour) {
        return hasKey(action, flavour) ? keyMap.labels(action, flavour) : keyMap.label(action, flavour);
    }

    @Override
    public int jumpToLatest() {
        return 0;
    }

    @Override
    public int getMaxOobPerSide() {
        return 3;
    }

    @Override
    public LogLine getLine(int lineNo, boolean ignore) {
        if (lineNo >= nowBoundary.line().get()) {
            return new NowBoundaryLine(lineNo, nowBoundary);
        }
        if (lineNo >= afterBoundary.line().get()) {
            return new AfterBoundaryLine(lineNo, afterBoundary);
        }
        if (beforeBoundary.line().isPresent() && lineNo < beforeBoundary.line().get()) {
            return new BeforeBoundaryLine(lineNo, beforeBoundary);
        }
        return new LoadedLogLine(RAW, "mood", lineNo, "cb", "bkt", now, null, now, now,
                styledLine(lineNo).text(), true, INFO);
    }

    @Override
    public List<StyleSpan> styleSpans(int lineNo) {
        if (lineNo >= afterBoundary.line().get()
                || (beforeBoundary.line().isPresent() && lineNo < beforeBoundary.line().get())) {
            return List.of();
        }
        return styledLine(lineNo).spans();
    }

    private synchronized StyledLine styledLine(int line) {
        if (line < 0) {
            int chunkIndex = (-line - 1) / SKY_CHUNK_HEIGHT;
            int rowInChunk = Math.floorMod(line, SKY_CHUNK_HEIGHT);
            StyledLine[] chunk = skyChunks.computeIfAbsent(chunkIndex, this::generateSkyChunk);
            return chunk[rowInChunk];
        }
        if (line < signLines.length) {
            if (line == loginInstructionLine) {
                syncLoginInstruction();
            }
            return signLines[line];
        }
        int licLine = line - signLines.length;
        if (licLine < LICENCE_LINES.length) {
            return LICENCE_LINES[licLine];
        }
        return StyledLine.EMPTY;
    }

    @Override
    public int jump(Instant timestamp) {
        return 0;
    }

    @Override
    public void unfreezeBeforeBoundary() {
        // Night sky is unbounded above row 0.
    }

    @Override
    public void unfreezeAfterBoundary() {
        // do nothing
    }

    @Override
    public boolean cancelSearch() {
        return false;
    }

    @Override
    public LogStatistics getStatistics() {
        Instant current = Instant.now();
        return new LogStatistics(LogStatistics.Status.UNINITIALISED, 0, current, current);
    }

    @Override
    public Boundary getBeforeBoundary() {
        return beforeBoundary;
    }

    @Override
    public void setBeforeBoundary(Instant t) {
        // do nothing
    }

    @Override
    public Boundary getAfterBoundary() {
        return afterBoundary;
    }

    @Override
    public void setAfterBoundary(Instant t) {
        // do nothing
    }

    @Override
    public Boundary getNowBoundary() {
        return nowBoundary;
    }

    @Override
    public void setBoundaryExpansionListener(BoundaryExpansionListener listener) {
        // do nothing - Mood doesn't support boundary expansion
    }

    @Override
    public SegmentInfo getSegmentInfo() {
        return SegmentInfo.uninitialized();
    }

    public synchronized String getHelpText() {
        syncLoginInstruction();
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < signLines.length; idx++) {
            if (idx > 0) {
                sb.append('\n');
            }
            sb.append(signLines[idx].text());
        }
        return sb.toString();
    }

    /**
     * Returns the line number of the injected login / scope instruction row.
     * Used to center this line on initial display with golden ratio positioning.
     */
    public synchronized int getLoginInstructionLine() {
        return loginInstructionLine;
    }

    private void appendKeyboardTable(List<StyledLine> out) {
        out.add(parseMarkup("## KEYBOARD SHORTCUTS"));
        String hint = "Every key works; hints show " + keyMap.flavour().displayName()
                + " keys first (Preferences: key hints). Dimmed: that column has no key of its own.";
        out.add(new StyledLine(hint, List.of(new StyleSpan(0, hint.length(), COMMENT, false))));
        out.add(StyledLine.EMPTY);

        int actionWidth = "Action".length();
        for (UiAction action : actions) {
            actionWidth = Math.max(actionWidth, action.description().length());
        }
        actionWidth += 3;

        int[] colWidths = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            KeyFlavour flavour = columns.get(c);
            int width = flavour.displayName().length();
            for (UiAction action : actions) {
                width = Math.max(width, cell(action, flavour).length());
            }
            colWidths[c] = width + (c + 1 < columns.size() ? 3 : 0);
        }

        out.add(buildHeaderRow(actionWidth, colWidths));

        for (UiAction.Group group : UiAction.Group.values()) {
            List<UiAction> grouped = actions.stream().filter(a -> a.group() == group).toList();
            if (grouped.isEmpty()) {
                continue;
            }
            out.add(StyledLine.EMPTY);
            String groupTitle = group.name().replace('_', ' ');
            out.add(new StyledLine(groupTitle, List.of(new StyleSpan(0, groupTitle.length(), CYAN, true))));
            for (UiAction action : grouped) {
                out.add(buildActionRow(action, actionWidth, colWidths));
            }
        }
    }

    private StyledLine buildHeaderRow(int actionWidth, int[] colWidths) {
        StringBuilder sb = new StringBuilder();
        List<StyleSpan> spans = new ArrayList<>(columns.size() + 1);

        appendCell(sb, spans, "Action", actionWidth, FG, true);
        for (int c = 0; c < columns.size(); c++) {
            boolean preferred = c == 0;
            appendCell(sb, spans, columns.get(c).displayName(), colWidths[c], preferred ? CYAN : FG, true);
        }
        return new StyledLine(sb.toString(), List.copyOf(spans));
    }

    private StyledLine buildActionRow(UiAction action, int actionWidth, int[] colWidths) {
        StringBuilder sb = new StringBuilder();
        List<StyleSpan> spans = new ArrayList<>(columns.size() + 1);

        appendCell(sb, spans, action.description(), actionWidth, FG, false);
        for (int c = 0; c < columns.size(); c++) {
            KeyFlavour flavour = columns.get(c);
            boolean preferred = c == 0;
            boolean own = hasKey(action, flavour);
            Supplier<Color> color = own ? (preferred ? CYAN : FG) : COMMENT;
            appendCell(sb, spans, cell(action, flavour), colWidths[c], color, preferred);
        }
        return new StyledLine(sb.toString(), List.copyOf(spans));
    }

    private static void appendCell(StringBuilder sb, List<StyleSpan> spans, String text, int width,
            Supplier<Color> color, boolean bold) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        if (end > start) {
            spans.add(new StyleSpan(start, end, color, bold));
        }
        for (int pad = end - start; pad < width; pad++) {
            sb.append(' ');
        }
    }

    /**
     * Parses lightweight inline markup once at construction time:
     * {@code # Title} and {@code ## Section} for bold orange headings,
     * {@code `key`} for bold cyan keys, and {@code **term**} for bold foreground text.
     */
    static StyledLine parseMarkup(String raw) {
        if (raw == null || raw.isEmpty()) {
            return StyledLine.EMPTY;
        }
        if (raw.startsWith("# ")) {
            String title = raw.substring(2);
            return new StyledLine(title, List.of(new StyleSpan(0, title.length(), ORANGE, true)));
        }
        if (raw.startsWith("## ")) {
            String heading = raw.substring(3);
            return new StyledLine(heading, List.of(new StyleSpan(0, heading.length(), ORANGE, true)));
        }

        StringBuilder plain = new StringBuilder(raw.length());
        List<StyleSpan> spans = new ArrayList<>();
        int idx = 0;
        while (idx < raw.length()) {
            char ch = raw.charAt(idx);
            if (ch == '`') {
                int close = raw.indexOf('`', idx + 1);
                if (close > idx + 1) {
                    int start = plain.length();
                    plain.append(raw, idx + 1, close);
                    spans.add(new StyleSpan(start, plain.length(), CYAN, true));
                    idx = close + 1;
                    continue;
                }
            } else if (ch == '*' && idx + 1 < raw.length() && raw.charAt(idx + 1) == '*') {
                int close = raw.indexOf("**", idx + 2);
                if (close > idx + 2) {
                    int start = plain.length();
                    plain.append(raw, idx + 2, close);
                    spans.add(new StyleSpan(start, plain.length(), FG, true));
                    idx = close + 2;
                    continue;
                }
            }
            plain.append(ch);
            idx++;
        }
        return new StyledLine(plain.toString(), spans.isEmpty() ? List.of() : List.copyOf(spans));
    }

    /** Styles the injected login / scope instruction row, highlighting its key names in bold cyan. */
    static StyledLine styleInstructionLine(String raw) {
        StyledLine parsed = parseMarkup(raw);
        if (!parsed.spans().isEmpty()) {
            return parsed;
        }
        String text = parsed.text();
        if (text.isEmpty()) {
            return StyledLine.EMPTY;
        }
        List<StyleSpan> spans = new ArrayList<>();
        spans.add(new StyleSpan(0, text.length(), FG, true));
        for (String token : new String[]{"Press ", " or ", " / "}) {
            int pos = 0;
            while ((pos = text.indexOf(token, pos)) >= 0) {
                int keyStart = pos + token.length();
                int keyEnd = keyStart;
                while (keyEnd < text.length() && !Character.isWhitespace(text.charAt(keyEnd))
                        && text.charAt(keyEnd) != ',') {
                    keyEnd++;
                }
                if (keyEnd > keyStart) {
                    String candidate = text.substring(keyStart, keyEnd);
                    if (!"to".equals(candidate) && !"select".equals(candidate) && !"change".equals(candidate)
                            && !"return".equals(candidate)) {
                        spans.add(new StyleSpan(keyStart, keyEnd, CYAN, true));
                    }
                }
                pos = keyEnd;
            }
        }
        return new StyledLine(text, List.copyOf(spans));
    }

    /** Two-tone retro styling for the 0x0A ASCII art logo: solid blocks in bold cyan, 3D bevels/shadows in purple. */
    private static StyledLine styleLogoLine(String line) {
        if (line.isEmpty()) {
            return StyledLine.EMPTY;
        }
        List<StyleSpan> spans = new ArrayList<>();
        int idx = 0;
        while (idx < line.length()) {
            char ch = line.charAt(idx);
            if (Character.isWhitespace(ch)) {
                idx++;
                continue;
            }
            boolean shadow = isLogoShadowChar(ch);
            int start = idx;
            idx++;
            while (idx < line.length() && !Character.isWhitespace(line.charAt(idx))
                    && isLogoShadowChar(line.charAt(idx)) == shadow) {
                idx++;
            }
            spans.add(new StyleSpan(start, idx, shadow ? PURPLE : CYAN, !shadow));
        }
        return new StyledLine(line, spans.isEmpty() ? List.of() : List.copyOf(spans));
    }

    private static boolean isLogoShadowChar(char ch) {
        return switch (ch) {
            case '░', '▒', '▓', '╔', '═', '╗', '║', '╚', '╝', '/' -> true;
            default -> false;
        };
    }

    private StyledLine[] generateSkyChunk(int chunkIndex) {
        Random rng = new Random(skySeed ^ (chunkIndex * 0x9E3779B97F4A7C15L));
        boolean firstChunk = chunkIndex == 0;
        double areaScale = (double) (skyWidth * SKY_CHUNK_HEIGHT) / (DEFAULT_SKY_WIDTH * SKY_CHUNK_HEIGHT);

        int moons = firstChunk ? 2 : (1 + rng.nextInt(2));
        boolean bigMoons = firstChunk || rng.nextInt(3) != 0;
        int aliens = firstChunk ? 1 : (rng.nextInt(4) == 0 ? 1 : 0);
        int surprises = firstChunk ? (rng.nextBoolean() ? 1 : 0) : (rng.nextInt(3) == 0 ? 1 : 0);
        int asteroids = Math.max(2, (int) Math.round((3 + rng.nextInt(2)) * areaScale));
        int bigStars = Math.max(3, (int) Math.round((6 + rng.nextInt(3)) * areaScale));
        int smallStars = Math.max(40, (int) Math.round((90 + rng.nextInt(30)) * areaScale));

        ASCIINightSkyGenerator generator = new ASCIINightSkyGenerator(skyWidth, SKY_CHUNK_HEIGHT, rng)
                .setMoonCount(moons)
                .setUseBigMoons(bigMoons)
                .setAsteroidCount(asteroids)
                .setBigStarCount(bigStars)
                .setSmallStarCount(smallStars)
                .setAlienCount(aliens)
                .setSurpriseCount(surprises)
                .setRequireNullSurrounding(true);

        String[] rawLines = generator.generateSkyChunk();
        StyledLine[] styled = new StyledLine[rawLines.length];
        for (int row = 0; row < rawLines.length; row++) {
            styled[row] = styleSkyLine(rawLines[row]);
        }
        return styled;
    }

    static StyledLine styleSkyLine(String line) {
        if (line.isEmpty()) {
            return StyledLine.EMPTY;
        }
        List<StyleSpan> spans = new ArrayList<>();
        int idx = 0;
        while (idx < line.length()) {
            char ch = line.charAt(idx);
            if (ch == ' ') {
                idx++;
                continue;
            }
            int start = idx;
            Supplier<Color> color = skyCharColor(line, idx);
            boolean bold = isBrightSkyChar(line, idx);
            idx++;
            while (idx < line.length() && line.charAt(idx) != ' ') {
                Supplier<Color> nextColor = skyCharColor(line, idx);
                boolean nextBold = isBrightSkyChar(line, idx);
                if (nextColor != color || nextBold != bold) {
                    break;
                }
                idx++;
            }
            spans.add(new StyleSpan(start, idx, color, bold));
        }
        return new StyledLine(line, spans.isEmpty() ? List.of() : List.copyOf(spans));
    }

    private static boolean isBrightSkyChar(String line, int idx) {
        char ch = line.charAt(idx);
        if (ch == '★' || ch == '✦' || ch == '⊹' || ch == '*' || ch == '+' || ch == '☾') {
            return true;
        }
        return ch == 'o' && isStarRayOrCenter(line, idx);
    }

    private static Supplier<Color> skyCharColor(String line, int idx) {
        char ch = line.charAt(idx);
        return switch (ch) {
            case '★', '*', '+', '☾', 'v', '○' -> YELLOW;
            case '~' -> BLUE;
            case '✦', '⊹' -> CYAN;
            case 'o', '|', '-' -> isStarRayOrCenter(line, idx) ? CYAN : COMMENT;
            case '◕', '‿', '✿', '☜', '☞', '♡', '♥', '◡', 'ʔ', 'ʕ' -> PINK;
            case '[', ']' -> isSatelliteBracket(line, idx) ? CYAN : PINK;
            default -> COMMENT;
        };
    }

    /**
     * Distinguishes star rays/centers ({@code -o-}, {@code --o--}, {@code - o -}, {@code |}, {@code [::]-o-[::]})
     * from moon/planet outlines ({@code |  |}, {@code '`-.}, {@code .-'""'-.}, {@code '-....-'}) so moons and
     * planets render with a uniform contour colour.
     */
    private static boolean isStarRayOrCenter(String line, int idx) {
        char ch = line.charAt(idx);
        char prev = idx > 0 ? line.charAt(idx - 1) : ' ';
        char next = idx + 1 < line.length() ? line.charAt(idx + 1) : ' ';
        if (ch == '|') {
            boolean moonPipePair = (idx + 3 < line.length() && line.charAt(idx + 1) == ' '
                    && line.charAt(idx + 2) == ' ' && line.charAt(idx + 3) == '|')
                    || (idx >= 3 && line.charAt(idx - 1) == ' '
                            && line.charAt(idx - 2) == ' ' && line.charAt(idx - 3) == '|');
            return !moonPipePair;
        }
        if (ch == '-') {
            if (prev == '.' || prev == '\'' || prev == '`' || prev == '~'
                    || next == '.' || next == '\'' || next == '`' || next == '~') {
                return false;
            }
            return prev == 'o' || prev == '+' || prev == '*' || prev == '-' || prev == ']' || prev == '|' || prev == ' '
                    || next == 'o' || next == '+' || next == '*' || next == '-' || next == '[' || next == '|'
                    || next == ' ';
        }
        if (ch == 'o') {
            return (prev == ' ' && next == ' ') || prev == '-' || next == '-';
        }
        return false;
    }

    private static boolean isSatelliteBracket(String line, int idx) {
        char prev = idx > 0 ? line.charAt(idx - 1) : ' ';
        char next = idx + 1 < line.length() ? line.charAt(idx + 1) : ' ';
        return prev == ':' || next == ':';
    }

    private static StyledLine[] buildLicenceLines() {
        String[] raw = SbomTransformer.transformSbom().lines().toArray(String[]::new);
        StyledLine[] styled = new StyledLine[raw.length];
        for (int idx = 0; idx < raw.length; idx++) {
            String line = raw[idx];
            styled[idx] = line.isEmpty()
                    ? StyledLine.EMPTY
                    : new StyledLine(line, List.of(new StyleSpan(0, line.length(), COMMENT, false)));
        }
        return styled;
    }

    private static final String ansiShadow = """
             ██████╗ ██╗  ██╗ ██████╗  █████╗
            ██╔═████╗╚██╗██╔╝██╔═████╗██╔══██╗
            ██║██╔██║ ╚███╔╝ ██║██╔██║███████║
            ████╔╝██║ ██╔██╗ ████╔╝██║██╔══██║
            ╚██████╔╝██╔╝ ██╗╚██████╔╝██║  ██║
             ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝
            """;

    private static final String ansiRegular = """
             ██████  ██   ██  ██████   █████
            ██  ████  ██ ██  ██  ████ ██   ██
            ██ ██ ██   ███   ██ ██ ██ ███████
            ████  ██  ██ ██  ████  ██ ██   ██
             ██████  ██   ██  ██████  ██   ██
            """;

    private static final String dosRebel = """
                █████                   █████      █████████
              ███░░░███               ███░░░███   ███░░░░░███
             ███   ░░███ █████ █████ ███   ░░███ ░███    ░███
            ░███    ░███░░███ ░░███ ░███    ░███ ░███████████
            ░███    ░███ ░░░█████░  ░███    ░███ ░███░░░░░███
            ░░███   ███   ███░░░███ ░░███   ███  ░███    ░███
             ░░░█████░   █████ █████ ░░░█████░   █████   █████
               ░░░░░░   ░░░░░ ░░░░░    ░░░░░░   ░░░░░   ░░░░░
            """;

    private static final String electronic = """
              ▄▄▄▄▄▄▄▄▄  ▄       ▄  ▄▄▄▄▄▄▄▄▄   ▄▄▄▄▄▄▄▄▄▄▄
             ▐░░░░░░░░░▌▐░▌     ▐░▌▐░░░░░░░░░▌ ▐░░░░░░░░░░░▌
            ▐░█░█▀▀▀▀▀█░▌▐░▌   ▐░▌▐░█░█▀▀▀▀▀█░▌▐░█▀▀▀▀▀▀▀█░▌
            ▐░▌▐░▌    ▐░▌ ▐░▌ ▐░▌ ▐░▌▐░▌    ▐░▌▐░▌       ▐░▌
            ▐░▌ ▐░▌   ▐░▌  ▐░▐░▌  ▐░▌ ▐░▌   ▐░▌▐░█▄▄▄▄▄▄▄█░▌
            ▐░▌  ▐░▌  ▐░▌   ▐░▌   ▐░▌  ▐░▌  ▐░▌▐░░░░░░░░░░░▌
            ▐░▌   ▐░▌ ▐░▌  ▐░▌░▌  ▐░▌   ▐░▌ ▐░▌▐░█▀▀▀▀▀▀▀█░▌
            ▐░▌    ▐░▌▐░▌ ▐░▌ ▐░▌ ▐░▌    ▐░▌▐░▌▐░▌       ▐░▌
            ▐░█▄▄▄▄▄█░█░▌▐░▌   ▐░▌▐░█▄▄▄▄▄█░█░▌▐░▌       ▐░▌
             ▐░░░░░░░░░▌▐░▌     ▐░▌▐░░░░░░░░░▌ ▐░▌       ▐░▌
              ▀▀▀▀▀▀▀▀▀  ▀       ▀  ▀▀▀▀▀▀▀▀▀   ▀         ▀
            """;

    private static final String mono9 = """
              ▄▄▄▄          ▄▄▄▄    ▄▄
             ▄▀  ▀▄ ▄   ▄  ▄▀  ▀▄   ██
             █  ▄ █  █▄█   █  ▄ █  █  █
             █    █  ▄█▄   █    █  █▄▄█
              █▄▄█  ▄▀ ▀▄   █▄▄█  █    █
            """;

    private static final String pagga = """
            ░▄▀▄░█░█░▄▀▄░█▀█
            ░█/█░▄▀▄░█/█░█▀█
            ░░▀░░▀░▀░░▀░░▀░▀
            """;

    private static final String smblock = """
            ▞▀▖   ▞▀▖▞▀▖
            ▌▞▌▚▗▘▌▞▌▙▄▌
            ▛ ▌▗▚ ▛ ▌▌ ▌
            ▝▀ ▘ ▘▝▀ ▘ ▘
            """;

    private static final String bigMono12 = """
              ░████░              ░████░     ▒██▒
              ██████              ██████     ▓██▓
             ▒██  ██▒            ▒██  ██▒    ████
             ██▒  ▒██  ███  ███  ██▒  ▒██    ████
             ██    ██   ██▒▒██   ██    ██   ▒█▓▓█▒
             ██ ██ ██   ▒████▒   ██ ██ ██   ▓█▒▒█▓
             ██ ██ ██    ████    ██ ██ ██   ██  ██
             ██    ██    ▒██▒    ██    ██   ██████
             ██▒  ▒██    ████    ██▒  ▒██  ░██████░
             ▒██  ██▒   ▒████▒   ▒██  ██▒  ▒██  ██▒
              ██████    ██▒▒██    ██████   ███  ███
              ░████░   ███  ███   ░████░   ██▒  ▒██
            """;

    private static final String[] moods = {ansiShadow, ansiRegular, dosRebel, electronic, mono9, pagga, smblock,
            bigMono12};

    private static final String overviewLines = """

            ## BROWSING & TIME
            Scroll with `Up`/`Down`, `PgUp`/`PgDn` (`b`/`Space`), or mouse wheel (`Ctrl` + arrows steps 8 lines/columns).
            Press `G` to jump to a timestamp, or `R` (or click the status bar time range) to set the time range.
            Press `F7` (`v` / `ScrLk`) to toggle **cursor mode**, and `Enter` on a cursor line to inspect **event details**.

            ## SEARCH & MATCHES
            Press `Ctrl-F` (`/`) to open the search prompt, and `F3` / `Shift-F3` (`n` / `N`, `Ctrl-S` / `Ctrl-R`) for next/previous match.
            Press `F4` (`&`) to toggle the **Matches** view (`Shift-Enter` in the search prompt searches and opens Matches).

            ## SCOPE & ACTIVITY
            Press `+` to **narrow** the scope to the active search's matches, or `-` to **exclude** them from the scope.
            Each row's leftmost Blockies icon encodes its activity (machine/process, thread, request):
            press `a` to narrow to its thread/process, `A` for its request, or click the icon / press `Shift-F10` for the menu.

            ## SELECTION, BOOKMARKS & EXPORT
            Hold `Shift` with movement keys, drag with the mouse, double-click (or double-tap `Tab`), or press `Ctrl-Space` to select.
            Press `Ctrl-C` to copy, `Ctrl-Shift-C` for formatted **Copy as…**, `Ctrl-B` (`m`) to bookmark, and `F2` (`M`) for **Bookmarks**.

            ## CANCEL CHAIN
            Press `Esc` (`Ctrl-G`) to back out one step at a time:
            time panel → help → prompt → search input → details → Bookmarks / Matches view → selection → active search (twice).

            """;

    // random date from the list
    static Instant randomInstant() {
        int idx = (int) (Math.random() * dates.size());
        return Instant.parse(dates.get(idx));
    }

    private static final List<String> dates = Arrays.asList("1903-12-17T15:35:00Z", // Wright brothers first flight
            "1783-11-21T12:00:00Z", // Montgolfier brothers first hot air balloon flight
            "1932-05-20T12:00:00Z", // Amelia Earhart first solo transatlantic flight
            "1969-02-09T12:00:00Z", // Boeing 747 first flight
            "1969-03-02T12:00:00Z", // Concorde first flight
            "1969-07-16T13:32:00Z", // Apollo 11 launch
            "1957-10-04T19:28:34Z", // Sputnik 1 launch
            "1961-04-12T06:06:59Z", // Yuri Gagarin first human in space
            "1957-11-03T02:30:42Z", // Laika first animal in orbit
            "1969-07-20T20:17:00Z", // Apollo 11 moon landing
            "1947-09-09T20:45:00Z", // Chuck Yeager breaks sound barrier
            "1969-10-30T06:30:00Z", // Boeing 747 first commercial flight
            "1947-12-23T12:00:00Z", // First jet airliner flight (de Havilland Comet)
            "1960-05-16T12:00:00Z", // First flight of the Lockheed SR-71 Blackbird
            "1958-01-28T12:58:00Z", // First flight of the Boeing 707
            "1958-10-18T12:00:00Z", // First flight of the Douglas DC-8
            "1930-02-18T12:00:00Z", // Charles Lindbergh first solo nonstop transcontinental flight
            "1928-09-28T12:00:00Z", // First flight of the Graf Zeppelin
            "1981-04-12T12:00:00Z" // First flight of the Space Shuttle Columbia
    );
}
