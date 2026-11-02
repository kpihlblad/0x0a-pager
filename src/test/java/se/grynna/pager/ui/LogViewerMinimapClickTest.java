package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.SegmentInfo;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.minimap.Minimap;
import se.grynna.pager.ui.minimap.MinimapModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Click and drag on the minimap as the viewer handles them, no window shown: a thousand rows numbered
 * {@code -500..499}, one event each ten seconds apart, a page of 20 rows. Synthetic mouse events go to the minimap
 * component; the viewport is read back from the cursor manager.
 */
class LogViewerMinimapClickTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int ROWS = 1000;
    private static final int FIRST_ROW = -500;
    private static final int PAGE = 20;

    private LogViewer viewer;
    private StatusBar statusBar;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider logProvider = mock(SplunkLogDataProvider.class);
        Instant end = T0.plusSeconds(10L * ROWS);
        when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, end));
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, end));
        when(logProvider.getStatistics()).thenReturn(new LogStatistics(LogStatistics.Status.LOADED, ROWS, T0, end));
        when(logProvider.getSegmentInfo()).thenReturn(new SegmentInfo(T0, T0.plusSeconds(10L * (ROWS - 1)), FIRST_ROW,
                FIRST_ROW + ROWS, ROWS, 0.0, Duration.ofSeconds(10L * (ROWS - 1)), T0, end, end));
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> line((int) inv.getArgument(0) - FIRST_ROW));
        when(logProvider.peekLine(anyInt())).thenAnswer(inv -> {
            int row = inv.getArgument(0);
            return row >= FIRST_ROW && row < FIRST_ROW + ROWS ? Optional.of(line(row - FIRST_ROW)) : Optional.empty();
        });
        when(logProvider.jump(any(SearchHit.class))).thenAnswer(inv -> rowOf(inv.getArgument(0)));

        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(logProvider);
        when(searchManager.scope()).thenReturn(Scope.of("web", Map.of()));
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(end.plusSeconds(3600));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            Field field = LogViewer.class.getDeclaredField("searchManager");
            field.setAccessible(true);
            field.set(viewer, searchManager);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
            viewer.cursorManager().updateSize(PAGE, 200);
            viewer.cursorManager().setPageStart(FIRST_ROW);
            viewer.updateVisibleLines();
        });
        awaitStrip();
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
    }

    // === Log view ===

    @Test
    void aClickAtTheBottomQuarterCentresThePageOnThatRow() throws Exception {
        onEdt(() -> {
            int y = minimap().getHeight() * 3 / 4;
            long target = MinimapModel.positionAt(new MinimapModel.Span(FIRST_ROW, FIRST_ROW + ROWS),
                    minimap().getHeight(), y);

            press(y);
            release();

            assertThat(target).isBetween(240L, 260L);
            assertThat(pageStart()).isEqualTo(target - PAGE / 2);
            assertThat(visibleFirstRow()).as("what the viewport shows follows").isEqualTo(pageStart());
            assertThat(target).isBetween((long) visibleFirstRow(), (long) visibleFirstRow() + PAGE - 1);
            assertThat(minimap().contains(minimap().getWidth() - 1, y)).isTrue();
        });
    }

    @Test
    void aClickAtTheVeryEndsKeepsThePageInsideTheLoadedRows() throws Exception {
        onEdt(() -> {
            press(minimap().getHeight() - 1);
            release();
            assertThat(pageStart()).isEqualTo(FIRST_ROW + ROWS - PAGE);

            press(0);
            release();
            assertThat(pageStart()).isEqualTo(FIRST_ROW);
        });
    }

    @Test
    void aClickTwoPixelsOffAMatchCentresTheMatch() throws Exception {
        onEdt(() -> {
            startSearch(300);
            viewer.updateVisibleLines();
        });
        awaitAmber();
        onEdt(() -> {
            int h = minimap().getHeight();
            MinimapModel.Span span = new MinimapModel.Span(FIRST_ROW, FIRST_ROW + ROWS);
            long match = FIRST_ROW + 300;
            int y = 0;
            while (MinimapModel.positionAt(span, h, y) < match) {
                y++;
            }

            press(y + 2);
            release();

            assertThat(pageStart()).as("the match is the middle row").isEqualTo(match - PAGE / 2);
            assertThat(visibleFirstRow()).isEqualTo(pageStart());
        });
    }

    @Test
    void draggingMovesTheViewportProgressively() throws Exception {
        onEdt(() -> {
            int h = minimap().getHeight();
            press(h / 2);
            int previous = pageStart();
            assertThat(previous).isEqualTo(-PAGE / 2);

            int y = h / 2 + 20;
            for (; y < h - 20; y += 40) {
                drag(y);
                assertThat(pageStart()).as("drag to %d", y).isGreaterThan(previous);
                previous = pageStart();
            }
            for (y -= 80; y > h / 4; y -= 40) {
                drag(y);
                assertThat(pageStart()).as("drag back to %d", y).isLessThan(previous);
                previous = pageStart();
            }
            release();
            drag(h - 1);
            assertThat(pageStart()).as("no drag after the release").isEqualTo(previous);
        });
    }

    @Test
    void aPressInsideTheVisibleRangeDoesNotJumpAndTheDragKeepsTheGrabOffset() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().setPageStart(0);
            viewer.updateVisibleLines();
            int h = minimap().getHeight();
            int start = pageStart();
            MinimapModel.Span visible = new MinimapModel.Span(start, start + PAGE);
            MinimapModel.Span span = new MinimapModel.Span(FIRST_ROW, FIRST_ROW + ROWS);
            MinimapModel.Band pixels = MinimapModel.visiblePixels(span, h, visible);
            int grabY = pixels.top();

            press(grabY);
            assertThat(pageStart()).as("a press inside does not move the page").isEqualTo(start);

            drag(grabY + 100);
            long moved = MinimapModel.positionAt(span, h, grabY + 100) - MinimapModel.positionAt(span, h, grabY);
            assertThat(pageStart()).isEqualTo(start + moved);
            release();
        });
    }

    @Test
    void aClickScrollsLikeTheWheel() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().gotoLine(-100);
            viewer.cursorManager().ensureCursorAt(-100);
            assertThat(viewer.cursorManager().hasNavigationHighlight()).isTrue();
            Integer cursorBefore = viewer.cursorManager().cursorLine().orElseThrow();
            boolean cursorMode = viewer.cursorManager().isCursorMode();

            press(minimap().getHeight() / 2);
            drag(minimap().getHeight() * 3 / 4);
            release();

            assertThat(viewer.cursorManager().cursorLine()).as("the cursor stays where it was")
                    .hasValue(cursorBefore);
            assertThat(viewer.cursorManager().isCursorMode()).isEqualTo(cursorMode);
            assertThat(viewer.cursorManager().getMark()).as("no selection starts").isEmpty();
            assertThat(viewer.cursorManager().hasNavigationHighlight()).isFalse();
            assertThat(pageStart()).isGreaterThan(0);
        });
    }

    @Test
    void theWheelOverTheStripScrollsTheLogLikeOverTheLog() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().setPageStart(0);
            JPanel viewPanel = viewPanel();
            viewPanel.dispatchEvent(wheel(viewPanel, 2));
            int overLog = pageStart();
            assertThat(overLog).isGreaterThan(0);

            viewer.cursorManager().setPageStart(0);
            Minimap minimap = minimap();
            minimap.dispatchEvent(wheel(minimap, 2));

            assertThat(pageStart()).isEqualTo(overLog);
        });
    }

    @Test
    void theStripKeepsKeyboardFocusOnTheLog() throws Exception {
        onEdt(() -> assertThat(minimap().isFocusable()).isFalse());
    }

    // === Matches view ===

    @Test
    void inTheMatchesViewAClickCentresTheFirstEventAtOrAfterThatTime() throws Exception {
        onEdt(() -> {
            startSearch(range(0, 1000, 10));
            viewer.updateVisibleLines();
            viewer.toggleMatches();
            assertThat(viewer.isMatchesView()).isTrue();
            viewer.cursorManager().updateSize(PAGE, 200);
            viewer.updateVisibleLines();
            assertThat(minimap().hasData()).isTrue();

            int h = minimap().getHeight();
            int y = h * 3 / 4;
            press(y);
            release();

            // 100 matches, one every 100 s from T0, in a window of 10 000 s.
            MinimapModel.Span window = MinimapModel.Span.ofInstants(T0, T0.plusSeconds(10_000).plusNanos(1));
            Instant clicked = MinimapModel.instantOf(MinimapModel.positionAt(window, h, y));
            long expectedEvent = (Duration.between(T0, clicked).toNanos() + 99_999_999_999L) / 100_000_000_000L;
            assertThat(expectedEvent).as("the click is in the bottom quarter").isBetween(70L, 80L);
            long centred = pageStart() + PAGE / 2;
            assertThat(centred).as("the middle row is the event at the click, rounded either way")
                    .isBetween(expectedEvent - 1, expectedEvent);
            List<Instant> shown = visibleLoaded().stream().map(LoadedLogLine::getSplunkTime).toList();
            assertThat(shown.get(PAGE / 2)).isEqualTo(T0.plusSeconds(centred * 100));
        });
    }

    @Test
    void inTheMatchesViewADragMovesThroughTheEvents() throws Exception {
        onEdt(() -> {
            startSearch(range(0, 1000, 10));
            viewer.updateVisibleLines();
            viewer.toggleMatches();
            viewer.cursorManager().updateSize(PAGE, 200);
            viewer.updateVisibleLines();
            int h = minimap().getHeight();

            press(h / 4);
            int previous = pageStart();
            for (int y = h / 4 + 30; y < h - 30; y += 30) {
                drag(y);
                assertThat(pageStart()).isGreaterThanOrEqualTo(previous);
                previous = pageStart();
            }
            assertThat(previous).isGreaterThan(40);
            release();
        });
    }

    // === pass-through ===

    @Test
    void inTheBookmarksViewNothingTakesTheMouse() throws Exception {
        onEdt(() -> {
            viewer.showBookmarks();
            assertThat(viewer.isBookmarksView()).isTrue();
            Minimap minimap = minimap();
            assertThat(minimap.hasData()).isFalse();
            for (int x = 0; x < minimap.getWidth(); x++) {
                assertThat(minimap.contains(x, 10)).isFalse();
            }
        });
    }

    @Test
    void beforeLoginNothingTakesTheMouse() throws Exception {
        onEdt(() -> {
            LogViewer mood = new LogViewer(Clock.systemUTC());
            mood.setSize(1024, 768);
            mood.doLayout();
            mood.updateVisibleLines();
            assertThat(mood.minimap().contains(mood.minimap().getWidth() - 1, 10)).isFalse();
        });
    }

    @Test
    void onlyTheStripAndItsMarginTakeTheMouse() throws Exception {
        onEdt(() -> {
            Minimap minimap = minimap();
            int y = minimap.getHeight() / 2;
            assertThat(minimap.contains(minimap.getWidth() - 1, y)).isTrue();
            assertThat(minimap.contains(Minimap.GLOW_REACH - Minimap.HIT_MARGIN, y)).isTrue();
            assertThat(minimap.contains(Minimap.GLOW_REACH - Minimap.HIT_MARGIN - 1, y)).isFalse();
        });
    }

    // === helpers ===

    private Minimap minimap() {
        return viewer.minimap();
    }

    private int pageStart() {
        return viewer.cursorManager().getPageStart();
    }

    private int visibleFirstRow() throws Exception {
        return visibleLoaded().getFirst().line();
    }

    private List<LoadedLogLine> visibleLoaded() throws Exception {
        List<LoadedLogLine> lines = new ArrayList<>();
        for (DecoratedLogLine decorated : viewer.cursorManager().getVisibleLines()) {
            if (decorated.getLogLine() instanceof LoadedLogLine loaded) {
                lines.add(loaded);
            }
        }
        return lines;
    }

    private void press(int y) {
        minimap().dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, y));
    }

    private void drag(int y) {
        minimap().dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, y));
    }

    private void release() {
        minimap().dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, 0));
    }

    private MouseEvent mouse(int id, int y) {
        int modifiers = id == MouseEvent.MOUSE_RELEASED ? 0 : MouseEvent.BUTTON1_DOWN_MASK;
        return new MouseEvent(minimap(), id, 0, modifiers, minimap().getWidth() - 1, y, 1, false,
                MouseEvent.BUTTON1);
    }

    private static MouseWheelEvent wheel(Component source, int rotation) {
        return new MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL, 0, 0, source.getWidth() - 1, 10, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
    }

    private JPanel viewPanel() throws Exception {
        Field field = LogViewer.class.getDeclaredField("viewPanel");
        field.setAccessible(true);
        return (JPanel) field.get(viewer);
    }

    private void awaitStrip() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean[] has = new boolean[1];
            SwingUtilities.invokeAndWait(() -> has[0] = viewer.minimap().hasData());
            assertThat(has[0]).isTrue();
        });
    }

    private static int[] range(int from, int to, int step) {
        return java.util.stream.IntStream.iterate(from, i -> i < to, i -> i + step).toArray();
    }

    /** The strip shows match density (amber) once the background row lookup has been applied. */
    private void awaitAmber() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean[] amber = new boolean[1];
            SwingUtilities.invokeAndWait(() -> amber[0] = hasAmber(paint(minimap())));
            assertThat(amber[0]).isTrue();
        });
    }

    private static boolean hasAmber(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            Color c = new Color(image.getRGB(Minimap.GLOW_REACH + 2, y), true);
            if (c.getAlpha() > 0 && c.getRed() > 180 && c.getGreen() > 120 && c.getBlue() < 100) {
                return true;
            }
        }
        return false;
    }

    private static BufferedImage paint(Minimap minimap) {
        BufferedImage image = new BufferedImage(Math.max(1, minimap.getWidth()), Math.max(1, minimap.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            minimap.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private void startSearch(int... matchEvents) {
        SearchSession active = new SearchSession("message", T0);
        for (int event : matchEvents) {
            SearchSessionTestSupport.addMatch(active, hit(event), line(event));
        }
        SearchSessionTestSupport.coverWindow(active, T0, T0.plusSeconds(10_000));
        SearchSessionTestSupport.complete(active);
        session.set(active);
        statusBar.setSearchActive(true, "message");
    }

    private static SearchHit hit(int index) {
        return SearchHit.from(line(index));
    }

    private static int rowOf(SearchHit hit) {
        return FIRST_ROW + (int) Duration.between(T0, hit.splunkTime()).toSeconds() / 10;
    }

    /** The event at {@code index} in the segment; its row is {@code FIRST_ROW + index}. */
    private static LoadedLogLine line(int index) {
        Instant time = T0.plusSeconds(10L * index);
        return new LoadedLogLine(MsgType.JSON, "seg", FIRST_ROW + index, "cb" + index, "bkt", time, "raw", time, time,
                "message " + index, false, Level.INFO);
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        return flatten(root).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<Component> flatten(Container root) {
        List<Component> all = new ArrayList<>();
        for (Component child : root.getComponents()) {
            all.add(child);
            if (child instanceof Container container) {
                all.addAll(flatten(container));
            }
        }
        return all;
    }

    private static void onEdt(ThrowingRunnable r) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            if (failure[0] instanceof Exception e) throw e;
            if (failure[0] instanceof Error e) throw e;
            throw new RuntimeException(failure[0]);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
