package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.ViewTabs.Tab;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The attention flashes of the status bar: the input bar's, and the hints on the Matches tab. Fake clock. */
class StatusBarAttentionFlashTest {

    private static final Instant START = Instant.parse("2017-05-01T12:00:00Z");

    private final AtomicLong clock = new AtomicLong(1_000);
    private StatusBar statusBar;
    private ViewTabs tabs;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities.invokeAndWait(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 14), viewer, clock::get);
            tabs = statusBar.viewTabs();
        });
    }

    // === Input bar ===

    @Test
    void nothingFlashesAtRest() throws Exception {
        onEdt(() -> assertThat(statusBar.inputFlash().isRunning()).isFalse());
    }

    @Test
    void enteringTheSearchInputFlashesTheInputBar() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_INPUT);
            assertThat(statusBar.inputFlash().isRunning()).isTrue();
        });
    }

    @Test
    void aYesNoQuestionFlashesTheInputBar() throws Exception {
        onEdt(() -> {
            statusBar.showScopeConfirmPrompt("2 bookmarks are outside the new scope. Continue?");
            assertThat(statusBar.inputState()).isEqualTo(InputState.SCOPE_CONFIRM);
            assertThat(statusBar.inputFlash().isRunning()).isTrue();
        });
    }

    @Test
    void theDoublePulseEndsAfterItsSecondPulse() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            clock.addAndGet(AttentionFlash.DOUBLE_DURATION_MILLIS + 1);
            assertThat(statusBar.inputFlash().isRunning()).isFalse();
        });
    }

    @Test
    void aWaitingBarRemindsAgainAfterTheRepeatInterval() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            clock.addAndGet(AttentionFlash.REPEAT_INTERVAL_MILLIS + AttentionFlash.POP_MILLIS);
            assertThat(statusBar.inputFlash().isRunning()).isTrue();
            assertThat(statusBar.inputFlash().intensity()).isEqualTo(1f);
        });
    }

    @Test
    void leavingTheWaitingStateStopsTheReminders() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.setSearchInputActive(false);
            assertThat(statusBar.inputFlash().isRepeating()).isFalse();
        });
    }

    @Test
    void reRaisingTheSamePromptFlashesAgain() throws Exception {
        onEdt(() -> {
            statusBar.showScopeConfirmPrompt("2 bookmarks are outside the new scope. Continue?");
            statusBar.inputFlash().stop();

            statusBar.showScopeConfirmPrompt("2 bookmarks are outside the new scope. Continue?");

            assertThat(statusBar.inputFlash().isRunning()).isTrue();
        });
    }

    @Test
    void prefillingAnOpenSearchInputFlashesAgain() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.inputFlash().stop();

            statusBar.prefillSearchInput("timeout");

            assertThat(statusBar.inputFlash().isRunning()).isTrue();
        });
    }

    @Test
    void aRerenderOfTheSameStateDoesNotFlashAgain() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.inputFlash().stop();

            statusBar.setSearchInputActive(true);
            statusBar.updateState();

            assertThat(statusBar.inputFlash().isRunning()).isFalse();
        });
    }

    @Test
    void aHitCountUpdateInTheActiveSearchDoesNotFlash() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.setSearchActive(true, "error");
            statusBar.inputFlash().stop();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_ACTIVE);

            statusBar.updateSearchHitCount(12, false, START, START.plusSeconds(60));
            statusBar.updateSearchHitCount(30, true, START, START.plusSeconds(60));

            assertThat(statusBar.inputFlash().isRunning()).isFalse();
        });
    }

    @Test
    void anotherQuestionReplacingTheCurrentOneFlashesAgain() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.inputFlash().stop();

            statusBar.showScopeConfirmPrompt("2 bookmarks are outside the new scope. Continue?");

            assertThat(statusBar.inputState()).isEqualTo(InputState.SCOPE_CONFIRM);
            assertThat(statusBar.inputFlash().isRunning()).isTrue();
        });
    }

    @Test
    void stateChangesThatNeedNoInputDoNotFlash() throws Exception {
        onEdt(() -> {
            statusBar.setMatchesMode(true);
            statusBar.setBookmarksMode(true);
            statusBar.setBookmarksMode(false);
            statusBar.setMatchesMode(false);
            statusBar.setModalLogin(true);
            statusBar.setModalLogin(false);
            assertThat(statusBar.inputFlash().isRunning()).isFalse();
        });
    }

    @Test
    void theInputBarIsBrighterAtThePeakOfTheFlashThanAtRest() throws Exception {
        onEdt(() -> {
            layOut(statusBar, 900);
            Rectangle field = boundsIn(statusBar, statusBar.inputField());
            long rest = brightness(paint(statusBar), field);

            statusBar.setSearchInputActive(true);
            clock.addAndGet(AttentionFlash.POP_MILLIS);
            assertThat(statusBar.inputFlash().intensity()).isEqualTo(1f);
            long peak = brightness(paint(statusBar), field);

            assertThat(peak).isGreaterThan(rest);

            clock.addAndGet(AttentionFlash.DURATION_MILLIS);
            assertThat(brightness(paint(statusBar), field)).isEqualTo(rest);
        });
    }

    @Test
    void theGlareBleedsOverTheStatusRowAboveTheInput() throws Exception {
        onEdt(() -> {
            layOut(statusBar, 900);
            Rectangle field = boundsIn(statusBar, statusBar.inputField());
            Rectangle above = new Rectangle(field.x + 20, field.y - 6, 200, 4);
            long rest = brightness(paint(statusBar), above);

            statusBar.setSearchInputActive(true);
            clock.addAndGet(AttentionFlash.POP_MILLIS);

            assertThat(brightness(paint(statusBar), above)).isGreaterThan(rest);
        });
    }

    @Test
    void aYesNoQuestionGlaresAroundItsPromptButton() throws Exception {
        onEdt(() -> {
            statusBar.showScopeConfirmPrompt("Continue?");
            layOut(statusBar, 900);
            statusBar.inputFlash().stop();
            Component button = findButton(statusBar, "Yes");
            Rectangle bounds = boundsIn(statusBar, button);
            assertThat(bounds.isEmpty()).isFalse();
            Rectangle above = new Rectangle(bounds.x, bounds.y - 6, bounds.width, 4);
            long rest = brightness(paint(statusBar), above);

            statusBar.inputFlash().start();
            clock.addAndGet(AttentionFlash.POP_MILLIS);

            assertThat(brightness(paint(statusBar), above)).isGreaterThan(rest);
        });
    }

    private static Component findButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) {
                return b;
            }
            if (child instanceof Container c) {
                Component found = findButton(c, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // === Matches tab hints ===

    @Test
    void theFirstMatchFlashesTheMatchesTab() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, START, START.plusSeconds(60));
            assertThat(tabs.isFlashing(Tab.MATCHES)).as("no match yet").isFalse();

            statusBar.updateSearchHitCount(3, false, START, START.plusSeconds(60));

            assertThat(tabs.isFlashing(Tab.MATCHES)).isTrue();
            assertThat(tabs.isFlashing(Tab.BOOKMARKS)).isFalse();
            assertThat(tabs.isFlashing(Tab.LOG)).isFalse();
        });
    }

    @Test
    void streamingIncreasesDoNotFlashAgain() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(3, false, START, START.plusSeconds(60));
            clock.addAndGet(AttentionFlash.DURATION_MILLIS + 10);
            assertThat(tabs.isFlashing(Tab.MATCHES)).isFalse();

            statusBar.updateSearchHitCount(40, false, START, START.plusSeconds(60));
            statusBar.updateSearchHitCount(90, false, START, START.plusSeconds(60));

            assertThat(tabs.isFlashing(Tab.MATCHES)).isFalse();
        });
    }

    @Test
    void completionWithMatchesFlashesOnceMore() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(3, false, START, START.plusSeconds(60));
            clock.addAndGet(AttentionFlash.DURATION_MILLIS + 10);

            statusBar.updateSearchHitCount(90, true, START, START.plusSeconds(60));

            assertThat(tabs.isFlashing(Tab.MATCHES)).isTrue();
        });
    }

    @Test
    void completionThroughThePhaseFlashesToo() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(3, false, START, START.plusSeconds(60));
            clock.addAndGet(AttentionFlash.DURATION_MILLIS + 10);

            statusBar.setTextSearchPhase(SearchStatusText.Phase.COMPLETE);

            assertThat(tabs.isFlashing(Tab.MATCHES)).isTrue();
        });
    }

    @Test
    void aSearchThatCompletesWithoutMatchesDoesNotFlash() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "nothing");
            statusBar.updateSearchHitCount(0, true, START, START.plusSeconds(60));
            assertThat(tabs.isFlashing(Tab.MATCHES)).isFalse();
        });
    }

    @Test
    void nothingFlashesWhileTheMatchesViewIsShown() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.setMatchesMode(true);

            statusBar.updateSearchHitCount(3, false, START, START.plusSeconds(60));
            statusBar.updateSearchHitCount(9, true, START, START.plusSeconds(60));

            assertThat(tabs.isFlashing(Tab.MATCHES)).isFalse();
        });
    }

    @Test
    void aNewSearchFlashesAgainOnItsFirstMatch() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "one");
            statusBar.updateSearchHitCount(3, true, START, START.plusSeconds(60));
            clock.addAndGet(AttentionFlash.DURATION_MILLIS + 10);

            statusBar.setSearchActive(true, "two");
            statusBar.updateSearchHitCount(1, false, START, START.plusSeconds(60));

            assertThat(tabs.isFlashing(Tab.MATCHES)).isTrue();
        });
    }

    @Test
    void flashBookmarksFlashesTheBookmarksTabUnlessItsViewIsShown() throws Exception {
        onEdt(() -> {
            statusBar.setBookmarksMode(true);
            statusBar.flashBookmarks();
            assertThat(tabs.isFlashing(Tab.BOOKMARKS)).isFalse();

            statusBar.setBookmarksMode(false);
            statusBar.flashBookmarks();
            assertThat(tabs.isFlashing(Tab.BOOKMARKS)).isTrue();
            assertThat(tabs.isFlashing(Tab.MATCHES)).isFalse();
        });
    }

    private static void layOut(Container container, int width) {
        if (container instanceof StatusBar) {
            container.setSize(width, container.getPreferredSize().height);
        }
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container c) {
                layOut(c, width);
            }
        }
    }

    private static Rectangle boundsIn(Container root, Component component) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
    }

    private static BufferedImage paint(StatusBar bar) {
        BufferedImage image = new BufferedImage(bar.getWidth(), bar.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            bar.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The sum of the red, green and blue values over {@code area}. */
    private static long brightness(BufferedImage image, Rectangle area) {
        Rectangle clipped = area.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        long sum = 0;
        for (int y = clipped.y; y < clipped.y + clipped.height; y++) {
            for (int x = clipped.x; x < clipped.x + clipped.width; x++) {
                int rgb = image.getRGB(x, y);
                sum += ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
            }
        }
        return sum;
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
            if (failure[0] instanceof Error e) throw e;
            if (failure[0] instanceof Exception e) throw e;
            throw new RuntimeException(failure[0]);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
