package se.grynna.pager.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.ui.AttentionFlash;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;
import se.grynna.pager.ui.InputState;
import se.grynna.pager.ui.LogViewer;
import se.grynna.pager.ui.StatusBar;
import se.grynna.pager.ui.TimeGroup;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dev tool, not a test: renders the status bar headless and writes PNGs, so the look of the view tabs and of the
 * attention flashes can be reviewed without a display. {@code viewtabs.png} shows the log, Matches and Bookmarks
 * views at rest; {@code viewtabs-flash.png} shows the flashes at their peak: the search input, a Y/N question, a
 * Matches tab hint and a Bookmarks tab hint.
 *
 * <pre>
 * mvn test -Dtest=StatusBarSnapshotTool -Dtest.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false \
 *     [-Dsnapshot.out=/tmp/opencode/viewtabs.png] [-Dsnapshot.flash.out=/tmp/opencode/viewtabs-flash.png] \
 *     [-Dsnapshot.width=1000]
 * </pre>
 */
@Tag("tool")
class StatusBarSnapshotTool {

    private static final Instant START = Instant.parse("2017-05-01T12:00:00Z");
    /** What the range text reads {@code now} from. */
    private static final Instant NOW = Instant.parse("2017-05-01T12:30:00Z");
    /** The flashes run on this clock, so a frame can be taken at an exact point of the timeline. */
    private static final AtomicLong CLOCK = new AtomicLong(1_000);
    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);

    @Test
    void render() throws Exception {
        String out = System.getProperty("snapshot.out", "/tmp/opencode/viewtabs.png");
        String flashOut = System.getProperty("snapshot.flash.out", "/tmp/opencode/viewtabs-flash.png");
        int width = Integer.getInteger("snapshot.width", 1000);

        SwingUtilities.invokeAndWait(() -> {
            PagerLaf.setup();
            DateTimeFormatterManagerTestUtil.initDTM();
        });

        List<StatusBar> rest = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 3; i++) {
                int view = i;
                rest.add(bar(width, bar -> {
                    bar.setBookmarkCount(view == 0 ? 0 : 3);
                    if (view > 0) {
                        bar.setSearchActive(true, "error");
                        bar.updateSearchHitCount(212, true, START, START.plusSeconds(600));
                    }
                    bar.setMatchesMode(view == 1);
                    bar.setBookmarksMode(view == 2);
                }));
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            // The browsing range of every bar starts at START-1h, so the step from there is 1h earlier.
            var request = new se.grynna.pager.ui.BoundaryRequest(se.grynna.pager.splunk.BoundaryType.BEFORE,
                    START.minusSeconds(2 * 3600), (t, a, b) -> {});
            rest.add(bar(width, bar -> bar.showBoundaryExpansionPrompt(request)));
            rest.add(bar(width, bar -> bar.showSearchRecentrePrompt(new se.grynna.pager.ui.BoundaryRequest(
                    se.grynna.pager.splunk.BoundaryType.BEFORE, Instant.parse("2017-04-28T09:00:00Z"),
                    (t, a, b) -> {}))));
            rest.add(bar(width, bar -> {
                bar.setSearchActive(true, "error");
                bar.updateSearchHitCount(212, true, START, START.plusSeconds(600));
                bar.showSearchExtendPrompt(request, "earlier");
            }));
            // The centre: browsing range is the default of every bar above; then a running
            // search, a running extension, zero matches over everything, an end note with a long range.
            rest.add(bar(width, bar -> {
                bar.setSearchActive(true, "error");
                bar.updateSearchHitCount(40, false, START, START.plusSeconds(600));
            }));
            rest.add(bar(width, bar -> {
                bar.setSearchActive(true, "error");
                bar.updateSearchHitCount(0, false, START, START.plusSeconds(600));
                bar.setSearchExtending(false, SearchBoundaries.MIN_MINIMUM_BOUNDARY);
            }));
            rest.add(bar(width, bar -> {
                bar.setSearchActive(true, "zeta");
                bar.updateSearchHitCount(0, true, SearchBoundaries.MIN_MINIMUM_BOUNDARY, NOW);
            }));
            rest.add(bar(width, bar -> {
                bar.setBrowsingRange(Instant.parse("2017-04-28T09:00:00Z"), NOW);
            }));
            rest.add(bar(width, bar -> {
                bar.setSearchActive(true, "error");
                bar.updateSearchHitCount(212, true, Instant.parse("2017-04-28T09:00:00Z"), START.plusSeconds(600));
                bar.setSearchPosition(212, 212, "no more later matches");
            }));
            rest.add(bar(width, bar -> bar.setSelectionSpan(new se.grynna.pager.ui.CursorManager.SelectionSpan(
                    START, START.plusSeconds(134)))));
        });
        CLOCK.addAndGet(10_000); // all flashes over
        write(out, width, paintAll(rest));

        List<StatusBar> flashing = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            flashing.add(bar(width, bar -> bar.setSearchInputActive(true)));
            flashing.add(
                    bar(width, bar -> bar.showScopeConfirmPrompt("2 bookmarks are outside the new scope. Continue?")));
            flashing.add(bar(width, bar -> {
                bar.setSearchActive(true, "error");
                bar.updateSearchHitCount(3, false, START, START.plusSeconds(600));
            }));
            flashing.add(bar(width, bar -> {
                bar.setBookmarkCount(3);
                bar.flashBookmarks();
            }));
            flashing.add(bar(width, bar -> {
                bar.setSearchActive(true, "zeta");
                bar.updateSearchHitCount(0, false, START.minusSeconds(600), START.plusSeconds(600));
                bar.updateSearchHitCount(0, false, START.minusSeconds(3600), START.plusSeconds(600));
            }));
            flashing.add(bar(width, bar -> {
                bar.setBrowsingRange(START.minusSeconds(3600), START.plusSeconds(600));
                bar.setBrowsingRange(Instant.parse("2017-04-28T09:00:00Z"), START.plusSeconds(600));
            }));
        });
        CLOCK.addAndGet(AttentionFlash.POP_MILLIS); // the top of the pop
        write(flashOut, width, paintAll(flashing));
    }

    private static StatusBar bar(int width, Consumer<StatusBar> setup) {
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        StatusBar bar = create(viewer);
        bar.setFirstVisibleTimestamp(START);
        bar.setBrowsingRange(START.minusSeconds(3600), START.plusSeconds(600));
        bar.setSize(width, bar.getPreferredSize().height);
        layout(bar);
        setup.accept(bar);
        invalidate(bar);
        bar.setSize(width, bar.getPreferredSize().height);
        layout(bar);
        // Include a hovered range half so its affordance is visible in the sheet.
        if (bar.isSearchActive()) hoverRange(bar);
        return bar;
    }

    private static void hoverRange(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof TimeGroup group) {
                group.dispatchEvent(new java.awt.event.MouseEvent(group, java.awt.event.MouseEvent.MOUSE_MOVED,
                        0, 0, group.getWidth() - 5, 5, 0, false));
            } else if (child instanceof Container container) hoverRange(container);
        }
    }

    private static StatusBar create(LogViewer viewer) {
        try {
            var constructor = StatusBar.class.getDeclaredConstructor(Font.class, LogViewer.class,
                    java.util.function.LongSupplier.class, java.time.InstantSource.class);
            constructor.setAccessible(true);
            return constructor.newInstance(FONT, viewer, (java.util.function.LongSupplier) CLOCK::get,
                    (java.time.InstantSource) () -> NOW);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<BufferedImage> paintAll(List<StatusBar> bars) throws Exception {
        List<BufferedImage> shots = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (StatusBar bar : bars) {
                BufferedImage shot = new BufferedImage(bar.getWidth(), bar.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = shot.createGraphics();
                bar.paint(g);
                g.dispose();
                shots.add(shot);
            }
        });
        return shots;
    }

    private static void write(String out, int width, List<BufferedImage> shots) throws Exception {
        int total = 0;
        for (BufferedImage shot : shots) {
            total += shot.getHeight() + 6;
        }
        BufferedImage all = new BufferedImage(width, total, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = all.createGraphics();
        int y = 0;
        for (BufferedImage shot : shots) {
            g.drawImage(shot, 0, y, null);
            y += shot.getHeight() + 6;
        }
        g.dispose();
        File file = new File(out);
        file.getParentFile().mkdirs();
        ImageIO.write(all, "png", file);
        System.out.println("Wrote " + file);
    }

    /** The real app revalidates after a change; here the cached preferred sizes are dropped by hand. */
    private static void invalidate(Container container) {
        container.invalidate();
        for (Component child : container.getComponents()) {
            if (child instanceof Container c) {
                invalidate(c);
            }
        }
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container c) {
                layout(c);
            }
        }
    }
}
