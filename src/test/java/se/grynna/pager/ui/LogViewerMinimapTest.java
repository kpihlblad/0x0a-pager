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

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * The minimap as the viewer drives it, no window shown: twelve rows, one event each, hits at some of them.
 * As in a real log, the rows are numbered around the jump target, so the first rows are negative. The row lookup
 * runs on the minimap's background thread, so tests wait for the strip to appear.
 */
class LogViewerMinimapTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int ROWS = 12;
    private static final int FIRST_ROW = -6;

    private LogViewer viewer;
    private StatusBar statusBar;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider logProvider = mock(SplunkLogDataProvider.class);
        when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, T0.plusSeconds(600)));
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T0.plusSeconds(600)));
        when(logProvider.getStatistics())
                .thenReturn(new LogStatistics(LogStatistics.Status.LOADED, ROWS, T0, T0.plusSeconds(600)));
        when(logProvider.getSegmentInfo()).thenReturn(new SegmentInfo(T0, T0.plusSeconds(10L * (ROWS - 1)), FIRST_ROW,
                FIRST_ROW + ROWS, ROWS, 0.0, Duration.ofSeconds(10L * (ROWS - 1)), T0, T0.plusSeconds(600),
                T0.plusSeconds(600)));
        when(logProvider.getLine(anyInt(), anyBoolean()))
                .thenAnswer(inv -> line((int) inv.getArgument(0) - FIRST_ROW));
        when(logProvider.peekLine(anyInt())).thenAnswer(inv -> {
            int row = inv.getArgument(0);
            return row >= FIRST_ROW && row < FIRST_ROW + ROWS ? Optional.of(line(row - FIRST_ROW)) : Optional.empty();
        });
        when(logProvider.jump(any(SearchHit.class))).thenAnswer(inv -> rowOf(inv.getArgument(0)));

        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(logProvider);
        when(searchManager.scope()).thenReturn(Scope.of("web", Map.of()));
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(T0.plusSeconds(3600));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            java.lang.reflect.Field field = LogViewer.class.getDeclaredField("searchManager");
            field.setAccessible(true);
            field.set(viewer, searchManager);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
            viewer.cursorManager().updateSize(50, 200);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
    }

    @Test
    void theStripIsThereWithoutASearchButHasNoMatches() throws Exception {
        onEdt(() -> viewer.updateVisibleLines());
        awaitStrip();
        onEdt(() -> {
            BufferedImage image = paint(viewer.minimap());
            assertThat(isEmpty(image)).as("the outline of the visible range").isFalse();
            assertThat(hasAmber(image)).as("no match density without a search").isFalse();
        });
    }

    @Test
    void theStripIsHiddenBeforeLogin() throws Exception {
        onEdt(() -> {
            LogViewer moodViewer = new LogViewer(Clock.systemUTC());
            moodViewer.setSize(1024, 768);
            moodViewer.doLayout();
            moodViewer.updateVisibleLines();
            assertThat(moodViewer.minimap().hasData()).isFalse();
            assertThat(isEmpty(paint(moodViewer.minimap()))).isTrue();
        });
    }

    @Test
    void theLogViewDrawsMatchesByRowAndTheBookmarksViewDrawsNothing() throws Exception {
        onEdt(() -> {
            startSearch(1, 3, 5, 7);
            viewer.updateVisibleLines();
        });
        awaitStrip();
        onEdt(() -> {
            Minimap minimap = viewer.minimap();
            assertThat(minimap.getWidth()).as("a strip of at least 4 px at the right edge").isGreaterThanOrEqualTo(4);
            assertThat(minimap.getX() + minimap.getWidth())
                    .isEqualTo(find(viewer, JLayeredPane.class).getFirst().getWidth());
            assertThat(hasAmber(paint(minimap))).isTrue();
        });

        onEdt(() -> {
            viewer.showBookmarks();
            assertThat(viewer.isBookmarksView()).isTrue();
            assertThat(viewer.minimap().hasData()).isFalse();
            assertThat(isEmpty(paint(viewer.minimap()))).isTrue();

            viewer.backToLog();
            viewer.updateVisibleLines();
        });
        awaitStrip();
    }

    @Test
    void theMatchesViewDrawsMatchesByTime() throws Exception {
        onEdt(() -> {
            startSearch(1, 3, 5, 7);
            firstVisible(1);
            viewer.toggleMatches();
            assertThat(viewer.isMatchesView()).isTrue();
        });
        // The time axis needs no row lookup: the strip is there straight away.
        onEdt(() -> {
            assertThat(viewer.minimap().hasData()).isTrue();
            assertThat(hasAmber(paint(viewer.minimap()))).isTrue();
        });
    }

    @Test
    void endingTheSearchKeepsTheStripButDropsTheMatches() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            viewer.updateVisibleLines();
        });
        awaitStrip();
        awaitAmber(true);

        onEdt(() -> {
            statusBar.setSearchActive(false, "");
            viewer.updateVisibleLines();
        });

        awaitAmber(false);
        onEdt(() -> assertThat(viewer.minimap().hasData()).isTrue());
    }

    // === helpers ===

    private void awaitStrip() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean[] has = new boolean[1];
            SwingUtilities.invokeAndWait(() -> has[0] = viewer.minimap().hasData());
            assertThat(has[0]).isTrue();
        });
    }

    private void awaitAmber(boolean expected) {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean[] has = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                viewer.updateVisibleLines();
                has[0] = hasAmber(paint(viewer.minimap()));
            });
            assertThat(has[0]).isEqualTo(expected);
        });
    }

    private void startSearch(int... matchEvents) {
        SearchSession active = new SearchSession("message", T0);
        for (int event : matchEvents) {
            SearchSessionTestSupport.addMatch(active, hit(event), line(event));
        }
        SearchSessionTestSupport.complete(active);
        session.set(active);
        statusBar.setSearchActive(true, "message");
    }

    /** The first visible event anchors the Matches view; there is no painted page to derive it from. */
    private void firstVisible(int event) throws Exception {
        java.lang.reflect.Field anchor = LogViewer.class.getDeclaredField("firstVisibleSearchHit");
        anchor.setAccessible(true);
        anchor.set(viewer, hit(event));
    }

    private static SearchHit hit(int row) {
        return SearchHit.from(line(row));
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

    private static boolean isEmpty(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) >>> 24 != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasAmber(BufferedImage image) {
        // Column 2 of the strip (after the glow gutter) is inside the slices and clear of the outline of the
        // visible range (the whole strip here).
        for (int y = 0; y < image.getHeight(); y++) {
            Color c = new Color(image.getRGB(Minimap.GLOW_REACH + 2, y), true);
            if (c.getAlpha() > 0 && c.getRed() > 180 && c.getGreen() > 120 && c.getBlue() < 100) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        return flatten(root).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<Component> flatten(Container root) {
        List<Component> all = new java.util.ArrayList<>();
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
