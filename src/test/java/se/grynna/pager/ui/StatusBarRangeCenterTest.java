package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.ui.ViewTabs.Tab;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In the status bar the centre always shows the range (browsing range or search window),
 * the match count lives only in the Matches tab, a growing range glares, and a running extension shows the edge it
 * moves to. Fake clocks for both {@code now} and the flash timeline; UTC.
 */
class StatusBarRangeCenterTest {

    private static final Instant NOW = Instant.parse("2017-05-01T12:00:00Z");
    private static final Instant T11 = Instant.parse("2017-05-01T11:00:00Z");
    private static final Instant T1130 = Instant.parse("2017-05-01T11:30:00Z");
    private static final Instant T1145 = Instant.parse("2017-05-01T11:45:00Z");
    private static final Instant T1150 = Instant.parse("2017-05-01T11:50:00Z");
    private static final Instant APR28 = Instant.parse("2017-04-28T09:00:00Z");
    private static final Instant MIN = SearchBoundaries.MIN_MINIMUM_BOUNDARY;
    private static final String ARROW = " \u2192 ";

    private final AtomicLong clock = new AtomicLong(1_000);
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities.invokeAndWait(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 14), viewer, clock::get, () -> NOW);
            statusBar.setFirstVisibleTimestamp(T1130);
        });
    }

    // === the centre without a search: the browsing range ===

    @Test
    void theCentreIsEmptyUntilALogIsBrowsed() throws Exception {
        onEdt(() -> {
            assertThat(statusBar.isCenterShown()).isFalse();
            assertThat(statusBar.searchCenterText()).isEmpty();
        });
    }

    @Test
    void theBrowsingRangeIsShownAsTimesOnTheAnchorsDate() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            assertThat(statusBar.isCenterShown()).isTrue();
            assertThat(statusBar.centerWord()).isEqualTo("RANGE");
            assertThat(statusBar.dateWord()).isEqualTo("AT");
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
            assertThat(statusBar.centerTooltip())
                    .isEqualTo(DateTimeFormatterManager.formatRangeFull(T11, T1145))
                    .startsWith(dateMinute(T11) + ":00");
        });
    }

    @Test
    void anEndAtOrAfterNowReadsNowAndTheFirstBoundaryReads1971() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, NOW.plusSeconds(86_400));
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + "now");

            statusBar.setBrowsingRange(MIN, T1145);
            assertThat(statusBar.searchCenterText()).isEqualTo("1971" + ARROW + time(T1145));

            statusBar.setBrowsingRange(MIN, Instant.MAX);
            assertThat(statusBar.searchCenterText()).isEqualTo("everything");

            statusBar.setBrowsingRange(APR28, NOW);
            assertThat(statusBar.searchCenterText()).isEqualTo(dateMinute(APR28) + ARROW + "now");
        });
    }

    @Test
    void theRangeFollowsTheFirstVisibleLinesDate() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.setFirstVisibleTimestamp(Instant.parse("2017-05-02T08:00:00Z"));
            assertThat(statusBar.searchCenterText()).isEqualTo(dateMinute(T11) + ARROW + dateMinute(T1145));
            statusBar.setFirstVisibleTimestamp(T1130);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
        });
    }

    @Test
    void clearingTheBrowsingRangeHidesTheCentre() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.setBrowsingRange(null, null);
            assertThat(statusBar.isCenterShown()).isFalse();
            assertThat(statusBar.searchCenterText()).isEmpty();
        });
    }

    // === the centre with a search: the window, prefixed ===

    @Test
    void aUserSetRangeDoesNotGlareButItsLaterGrowthDoes() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T1130, T1145);
            statusBar.resetRangeBaseline();
            statusBar.setBrowsingRange(T11, T1150);
            assertThat(statusBar.centerFlash().isRunning()).isFalse();
            assertThat(statusBar.centerTooltip()).isEqualTo(DateTimeFormatterManager.formatRangeFull(T11, T1150));
            statusBar.setBrowsingRange(T11, NOW);
            assertThat(statusBar.centerFlash().isRunning()).isTrue();
        });
    }

    @Test
    void aRunningSearchShowsSearchingAndACompleteOneShowsSearched() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(5, false, T11, T1145);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
            assertThat(statusBar.centerWord()).isEqualTo("SEARCHING");

            statusBar.updateSearchHitCount(5, true, T11, T1145);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
            assertThat(statusBar.centerWord()).isEqualTo("SEARCHED");

            statusBar.setTextSearchPhase(SearchStatusText.Phase.STOPPED);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
            assertThat(statusBar.centerWord()).isEqualTo("SEARCHED");
            assertThat(statusBar.centerTooltip()).isEqualTo(DateTimeFormatterManager.formatRangeFull(T11, T1145));
        });
    }

    @Test
    void aSearchWithoutAWindowYetShowsTheBrowsingRangeAsSearching() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.setSearchActive(true, "error");
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
        });
    }

    @Test
    void zeroMatchesCompleteSaysNoMatchesAndWhatWasSearched() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "zeta");
            statusBar.updateSearchHitCount(0, true, MIN, NOW);
            assertThat(statusBar.searchCenterText()).isEqualTo("No matches \u00b7 everything");

            statusBar.setSearchActive(true, "zeta");
            statusBar.updateSearchHitCount(0, true, T11, NOW);
            assertThat(statusBar.searchCenterText())
                    .isEqualTo("No matches \u00b7 " + time(T11) + ARROW + "now");

            statusBar.setSearchActive(true, "zeta");
            statusBar.updateSearchHitCount(0, false, T11, NOW);
            assertThat(statusBar.searchCenterText()).as("not while it still runs")
                    .isEqualTo(time(T11) + ARROW + "now");
        });
    }

    @Test
    void anEdgeNoteKeepsItsOrangeFormWithTheRange() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(12, true, T11, T1145);
            statusBar.setSearchPosition(12, 12, "no more later matches");
            assertThat(statusBar.searchCenterText())
                    .isEqualTo("No more later matches  \u00b7  " + time(T11) + ARROW + time(T1145));
            assertThat(centerColor()).isEqualTo(DarculaColors.ORANGE);

            statusBar.clearSearchPosition();
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
            assertThat(centerColor()).isEqualTo(DarculaColors.FOREGROUND);
        });
    }

    @Test
    void endingTheSearchGoesBackToTheBrowsingRange() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(5, true, T1130, T1145);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T1130) + ARROW + time(T1145));

            statusBar.setSearchActive(false, null);
            statusBar.clearSearchInfo();
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
        });
    }

    // === one owner per fact: the count is the Matches tab's ===

    @Test
    void theCountIsOnlyInTheMatchesTabAndInsideAPosition() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(212, false, T11, T1145);
            assertThat(statusBar.viewTabs().labelOf(Tab.MATCHES)).isEqualTo("MATCHES 212");
            assertThat(statusBar.searchCenterText()).doesNotContain("212").doesNotContain("matches");
            assertThat(statusBar.searchPromptText()).contains("\"error\" \u2014 searching\u2026")
                    .doesNotContain("212");

            statusBar.updateSearchHitCount(212, true, T11, T1145);
            assertThat(statusBar.searchPromptText()).contains("\"error\"").doesNotContain("212")
                    .doesNotContain("\u2014");
            assertThat(statusBar.searchCenterText()).doesNotContain("212");
            assertThat(statusBar.viewTabs().labelOf(Tab.MATCHES)).isEqualTo("MATCHES 212");

            statusBar.setSearchPosition(3, 212, null);
            assertThat(statusBar.searchPromptText()).contains("3 of 212");
            assertThat(statusBar.searchCenterText()).doesNotContain("212");
        });
    }

    @Test
    void theTabKeepsTheProvisionalAndTruncationMarkers() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.setSearchProvisional(true);
            statusBar.setSearchTruncation(true, false, false);
            statusBar.updateSearchHitCount(10_000, true, T11, T1145);
            assertThat(statusBar.viewTabs().labelOf(Tab.MATCHES)).isEqualTo("MATCHES ~10,000+");
            assertThat(statusBar.searchPromptText()).doesNotContain("10,000");
            assertThat(statusBar.searchCenterText()).doesNotContain("10,000");
        });
    }

    @Test
    void thePromptSaysNoMatchesOnlyForAFinishedEmptySearchAndStoppedForAStoppedOne() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "zeta");
            statusBar.updateSearchHitCount(0, true, T11, T1145);
            assertThat(statusBar.searchPromptText()).contains("\"zeta\" \u2014 no matches");

            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(4, false, T11, T1145);
            statusBar.setTextSearchPhase(SearchStatusText.Phase.STOPPED);
            assertThat(statusBar.searchPromptText()).contains("\"error\" \u2014 stopped");
        });
    }

    // === visible extensions: the glare ===

    @Test
    void theFirstRangeDoesNotGlare() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            assertThat(statusBar.centerFlash().isRunning()).isFalse();

            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, T1130, T1145);
            assertThat(statusBar.centerFlash().isRunning()).as("the first window of a search").isFalse();
        });
    }

    @Test
    void aBrowsingRangeThatGrowsGlaresEachStep() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T1130, T1145);

            statusBar.setBrowsingRange(T11, T1145);
            assertThat(statusBar.centerFlash().isRunning()).as("earlier start").isTrue();

            clock.addAndGet(AttentionFlash.DURATION_MILLIS + 10);
            assertThat(statusBar.centerFlash().isRunning()).isFalse();

            statusBar.setBrowsingRange(T11, T1150);
            assertThat(statusBar.centerFlash().isRunning()).as("later end").isTrue();
        });
    }

    @Test
    void aSearchWindowThatGrowsGlaresEachStep() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, T1130, T1145);

            statusBar.updateSearchHitCount(0, false, T11, T1145);
            assertThat(statusBar.centerFlash().isRunning()).isTrue();

            clock.addAndGet(AttentionFlash.DURATION_MILLIS + 10);
            statusBar.updateSearchHitCount(0, false, MIN, T1145);
            assertThat(statusBar.centerFlash().isRunning()).as("each automatic step glares again").isTrue();
        });
    }

    @Test
    void anUnchangedShrunkOrReplacedRangeDoesNotGlare() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, T11, T1145);

            statusBar.updateSearchHitCount(7, false, T11, T1145);
            assertThat(statusBar.centerFlash().isRunning()).as("unchanged: only the count moved").isFalse();

            statusBar.updateSearchHitCount(7, true, T1130, T1145);
            assertThat(statusBar.centerFlash().isRunning()).as("shrunk").isFalse();

            statusBar.setSearchActive(true, "other");
            statusBar.updateSearchHitCount(0, false, MIN, T1145);
            assertThat(statusBar.centerFlash().isRunning()).as("a new search is not an extension").isFalse();

            statusBar.setSearchActive(false, null);
            statusBar.clearSearchInfo();
            statusBar.setBrowsingRange(T1130, T1145);
            assertThat(statusBar.centerFlash().isRunning()).as("back to browsing").isFalse();
        });
    }

    @Test
    void aMovedRangeIsNotAGrowth() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.setBrowsingRange(Instant.parse("2017-04-30T00:00:00Z"), Instant.parse("2017-05-01T00:00:00Z"));
            assertThat(statusBar.centerFlash().isRunning()).as("a jump to another day").isFalse();
        });
    }

    @Test
    void aGrowthThatLeavesTheTextUnchangedDoesNotGlare() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, T11, NOW.minusSeconds(120));
            // Both ends read "now" (within five minutes): the text is the same.
            statusBar.updateSearchHitCount(0, false, T11, NOW);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + "now");
            assertThat(statusBar.centerFlash().isRunning()).isFalse();
        });
    }

    @Test
    void theGlarePaintsOverTheCentreAtThePeakAndNotAtRest() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T1130, T1145);
            statusBar.setBrowsingRange(T11, T1145);
            layOut(statusBar, 1000);
            Rectangle text = centerTextBox();
            assertThat(text.isEmpty()).isFalse();
            // The glare bleeds over the input row under the text.
            Rectangle below = new Rectangle(text.x, text.y + text.height + 1, text.width, 6);

            clock.addAndGet(AttentionFlash.POP_MILLIS);
            assertThat(statusBar.centerFlash().intensity()).isEqualTo(1f);
            long peak = brightness(paint(statusBar), below);

            clock.addAndGet(AttentionFlash.DURATION_MILLIS);
            assertThat(statusBar.centerFlash().intensity()).isZero();
            assertThat(brightness(paint(statusBar), below)).isLessThan(peak);
        });
    }

    // === visible extensions: the moving edge ===

    @Test
    void aRunningExtensionShowsTheEdgeItMovesTowards() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, T1130, T1145);

            statusBar.setSearchExtending(false, APR28);
            assertThat(statusBar.searchCenterText()).isEqualTo("\u2190 " + dateMinute(APR28));

            statusBar.setSearchExtending(false, MIN);
            assertThat(statusBar.searchCenterText()).isEqualTo("\u2190 1971");
            assertThat(statusBar.centerWord()).isEqualTo("SEARCHING");

            statusBar.setSearchExtending(true, NOW);
            assertThat(statusBar.searchCenterText()).isEqualTo("\u2192 now");

            statusBar.setSearchExtending(true, T1150);
            assertThat(statusBar.searchCenterText()).isEqualTo("\u2192 " + time(T1150));
        });
    }

    @Test
    void theMovingEdgeEndsWithTheExtensionAndTheNextSearch() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, T1130, T1145);
            statusBar.setSearchExtending(false, T11);
            statusBar.updateSearchHitCount(2, false, T1130, T1145);
            assertThat(statusBar.searchCenterText()).as("still running").isEqualTo("\u2190 " + time(T11));

            statusBar.updateSearchHitCount(2, true, T11, T1145);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));

            statusBar.setSearchExtending(false, MIN);
            statusBar.setSearchActive(true, "next");
            statusBar.updateSearchHitCount(0, false, T11, T1145);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T11) + ARROW + time(T1145));
        });
    }

    @Test
    void aStoppedExtensionDropsTheMovingEdge() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(0, false, T1130, T1145);
            statusBar.setSearchExtending(true, NOW);
            statusBar.setTextSearchPhase(SearchStatusText.Phase.STOPPED);
            assertThat(statusBar.searchCenterText()).isEqualTo(time(T1130) + ARROW + time(T1145));
        });
    }

    // === a narrow label drops words, not the end of the range ===

    @Test
    void aNarrowRangeHalfDropsTheRangeAndWidensAgain() throws Exception {
        Instant from = APR28;
        String full = "No more later matches  \u00b7  " + dateMinute(APR28) + ARROW + time(T1145);
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(12, true, from, T1145);
            statusBar.setSearchPosition(12, 12, "no more later matches");
            statusBar.centerLabel().setSize(1200, 20);
        });
        onEdt(() -> assertThat(statusBar.searchCenterText()).isEqualTo(full));

        onEdt(() -> statusBar.centerLabel().setSize(1000, 20));
        onEdt(() -> assertThat(statusBar.searchCenterText())
                .isEqualTo("No more later matches  \u00b7  " + dateMinute(APR28) + ARROW + time(T1145)));

        onEdt(() -> statusBar.centerLabel().setSize(550, 20));
        onEdt(() -> assertThat(statusBar.searchCenterText()).isEqualTo("No more later matches"));

        onEdt(() -> statusBar.centerLabel().setSize(1200, 20));
        onEdt(() -> assertThat(statusBar.searchCenterText()).isEqualTo(full));
    }

    @Test
    void anEdgeNoteSizesTheGroupByItsShortFormSoItCannotStretchTheRow() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(12, true, APR28, T1145);
            statusBar.setSearchPosition(12, 12, "no more later matches");
            TimeGroup group = statusBar.centerLabel();
            String full = "No more later matches  \u00b7  " + dateMinute(APR28) + ARROW + time(T1145);
            FontMetrics fm = group.getFontMetrics(group.getFont());
            group.setSize(group.getPreferredSize());
            int atWidth = group.halfBounds(TimeGroup.Half.AT).width;
            // The group reserves less than the full note+range text would need …
            assertThat(group.getWidth()).isLessThan(atWidth + fm.stringWidth(full));
            // … and therefore paints the short form at its own preferred size.
            assertThat(statusBar.searchCenterText()).isEqualTo("No more later matches");
        });
    }

    // === helpers ===

    @Test
    void timeGroupRoutesBothHalvesAndDisablesWelcomeClicksAndHover() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            TimeGroup group = statusBar.centerLabel();
            group.setSize(group.getPreferredSize());
            java.util.List<TimeGroup.Half> clicked = new java.util.ArrayList<>();
            statusBar.setTimeGroupListener(clicked::add);
            assertThat(group.isFocusable()).isFalse();
            for (TimeGroup.Half half : TimeGroup.Half.values()) {
                Rectangle bounds = group.halfBounds(half);
                int x = bounds.x + bounds.width / 2;
                MouseEvent event = new MouseEvent(group, MouseEvent.MOUSE_CLICKED, 0, 0, x, 5, 1, false,
                        MouseEvent.BUTTON1);
                group.dispatchEvent(event);
                assertThat(group.getToolTipText(event)).startsWith(half == TimeGroup.Half.AT
                        ? "Go to a time (" + KeyMap.preferred().label(UiAction.GOTO) + ")"
                        : "Set the time range (" + KeyMap.preferred().label(UiAction.SET_RANGE) + ")\n"
                                + statusBar.centerTooltip());
            }
            assertThat(clicked).containsExactly(TimeGroup.Half.AT, TimeGroup.Half.RANGE);
            statusBar.setSelectionSpan(new CursorManager.SelectionSpan(T11, T1145));
            assertThat(statusBar.dateWord()).isEqualTo("SPAN");
            statusBar.setSelectionSpan(null);
            assertThat(statusBar.dateWord()).isEqualTo("AT");
            statusBar.setBrowsingRange(null, null);
            statusBar.setFirstVisibleTimestamp(null);
            group.dispatchEvent(new MouseEvent(group, MouseEvent.MOUSE_MOVED, 0, 0, 5, 5, 0, false));
            group.dispatchEvent(
                    new MouseEvent(group, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1));
            assertThat(group.isEnabled()).isFalse();
            assertThat(group.getCursor().getType()).isEqualTo(java.awt.Cursor.DEFAULT_CURSOR);
            assertThat(clicked).hasSize(2);
        });
    }

    /** The formatter manager is a singleton, so its zone is whatever the first test to run chose. */
    private static String time(Instant t) {
        return DateTimeFormatterManagerTestUtil.localTime(t);
    }

    private static String dateMinute(Instant t) {
        return DateTimeFormatterManagerTestUtil.localDateMinute(t);
    }

    private Color centerColor() {
        return statusBar.centerLabel().getForeground();
    }

    private Rectangle centerTextBox() {
        TimeGroup label = statusBar.centerLabel();
        Rectangle bounds = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), statusBar);
        Rectangle half = label.halfBounds(TimeGroup.Half.RANGE);
        return new Rectangle(bounds.x + half.x, bounds.y, half.width, bounds.height);
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
