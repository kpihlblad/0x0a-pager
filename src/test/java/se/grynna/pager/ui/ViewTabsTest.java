package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.ViewTabs.Tab;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The painted view tabs: labels, counts, active tab, tooltips and click routing, headless. */
class ViewTabsTest {

    private ViewTabs tabs;
    private final List<Tab> selected = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
        SwingUtilities.invokeAndWait(() -> {
            tabs = new ViewTabs(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            tabs.setOnSelect(selected::add);
            tabs.setSize(tabs.getPreferredSize());
        });
    }

    @AfterEach
    void tearDown() {
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
    }

    @Test
    void theLogTabIsActiveAtFirstAndTheLabelsAreTheNames() throws Exception {
        onEdt(() -> {
            assertThat(tabs.active()).isEqualTo(Tab.LOG);
            assertThat(tabs.labelOf(Tab.LOG)).isEqualTo("LOG");
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES");
            assertThat(tabs.labelOf(Tab.BOOKMARKS)).isEqualTo("BOOKMARKS");
            assertThat(tabs.labelOf(Tab.DETAILS)).isEqualTo("DETAILS");
        });
    }

    @Test
    void theCountsFollowTheNamesAndTheBookmarkCountHidesAtZero() throws Exception {
        onEdt(() -> {
            tabs.setMatchCount("~212");
            tabs.setBookmarkCount(3);
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES ~212");
            assertThat(tabs.labelOf(Tab.BOOKMARKS)).isEqualTo("BOOKMARKS 3");

            tabs.setMatchCount(null);
            tabs.setBookmarkCount(0);
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES");
            assertThat(tabs.labelOf(Tab.BOOKMARKS)).isEqualTo("BOOKMARKS");
        });
    }

    @Test
    void aCountWidensItsTabAndMovesTheOnesAfterIt() throws Exception {
        onEdt(() -> {
            int before = tabs.tabBounds(Tab.MATCHES).width;
            int bookmarksX = tabs.tabBounds(Tab.BOOKMARKS).x;

            tabs.setMatchCount("5000+");

            assertThat(tabs.tabBounds(Tab.MATCHES).width).isGreaterThan(before);
            assertThat(tabs.tabBounds(Tab.BOOKMARKS).x).isGreaterThan(bookmarksX);
            assertThat(tabs.getPreferredSize().width).isGreaterThanOrEqualTo(tabs.tabBounds(Tab.DETAILS).x
                    + tabs.tabBounds(Tab.DETAILS).width);
        });
    }

    @Test
    void anyTabCanBeActiveAndNullIsIgnored() throws Exception {
        onEdt(() -> {
            tabs.setActive(Tab.MATCHES);
            assertThat(tabs.active()).isEqualTo(Tab.MATCHES);
            tabs.setActive(Tab.BOOKMARKS);
            assertThat(tabs.active()).isEqualTo(Tab.BOOKMARKS);
            tabs.setActive(Tab.DETAILS);
            assertThat(tabs.active()).isEqualTo(Tab.DETAILS);
            tabs.setActive(null);
            assertThat(tabs.active()).isEqualTo(Tab.DETAILS);
            assertThat(tooltip(Tab.DETAILS)).isEqualTo("Close details (Esc)");
            tabs.setActive(Tab.LOG);
            assertThat(tabs.active()).isEqualTo(Tab.LOG);
        });
    }

    @Test
    void theTabsAreLaidOutLeftToRightWithoutOverlapAndTabAtFindsThem() throws Exception {
        onEdt(() -> {
            Tab[] all = Tab.values();
            for (int i = 0; i < all.length; i++) {
                Rectangle bounds = tabs.tabBounds(all[i]);
                assertThat(bounds.height).isEqualTo(tabs.getHeight());
                assertThat(tabs.tabAt(bounds.x)).isEqualTo(all[i]);
                assertThat(tabs.tabAt(bounds.x + bounds.width - 1)).isEqualTo(all[i]);
                if (i > 0) {
                    Rectangle previous = tabs.tabBounds(all[i - 1]);
                    assertThat(bounds.x).isGreaterThan(previous.x + previous.width - 1);
                }
            }
            assertThat(tabs.tabAt(-1)).isNull();
            assertThat(tabs.tabAt(tabs.getWidth() + 50)).isNull();
        });
    }

    @Test
    void theStripIsNotFocusableAndTransparent() throws Exception {
        onEdt(() -> {
            assertThat(tabs.isFocusable()).isFalse();
            assertThat(tabs.isOpaque()).isFalse();
            assertThat(tabs.getCursor().getType()).isEqualTo(java.awt.Cursor.HAND_CURSOR);
        });
    }

    @Test
    void theTooltipsNameTheActionAndItsKey() throws Exception {
        onEdt(() -> {
            assertThat(tooltip(Tab.LOG)).isEqualTo("Back to the log (Esc)");
            assertThat(tooltip(Tab.MATCHES)).isEqualTo("Show the matches (F4)");
            assertThat(tooltip(Tab.BOOKMARKS)).isEqualTo("Show the bookmarks (F2)");
            assertThat(tooltip(Tab.DETAILS)).isEqualTo("Details for the cursor line (Enter)");
        });
    }

    @Test
    void theTooltipsFollowTheKeyHintsPreferenceWhenAsked() throws Exception {
        KeyMap.setPreferredFlavour(KeyFlavour.PAGER);
        onEdt(() -> {
            assertThat(tooltip(Tab.MATCHES))
                    .isEqualTo("Show the matches (" + KeyMap.preferred().label(UiAction.TOGGLE_MATCHES) + ")");
            assertThat(tooltip(Tab.BOOKMARKS)).isEqualTo("Show the bookmarks (M)");
        });
    }

    @Test
    void noTooltipOutsideTheTabs() throws Exception {
        onEdt(() -> assertThat(tabs.getToolTipText(mouse(MouseEvent.MOUSE_MOVED, tabs.getWidth() + 20, 1))).isNull());
    }

    @Test
    void aClickOnATabSelectsIt() throws Exception {
        onEdt(() -> {
            for (Tab tab : Tab.values()) {
                click(tabs.tabBounds(tab).x + 3);
            }
            assertThat(selected).containsExactly(Tab.LOG, Tab.MATCHES, Tab.BOOKMARKS, Tab.DETAILS);
        });
    }

    @Test
    void aClickOutsideTheTabsOrWithAnotherButtonSelectsNothing() throws Exception {
        onEdt(() -> {
            click(tabs.getWidth() + 30);
            tabs.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON3_DOWN_MASK, 3, 1, 1,
                    false, MouseEvent.BUTTON3));
            assertThat(selected).isEmpty();
        });
    }

    @Test
    void clickingTheActiveTabSelectsItAsWell() throws Exception {
        onEdt(() -> {
            tabs.setActive(Tab.MATCHES);
            click(tabs.tabBounds(Tab.MATCHES).x + 2);
            assertThat(selected).containsExactly(Tab.MATCHES);
        });
    }

    @Test
    void hoveringAndLeavingRepaintsWithoutChangingTheActiveTab() throws Exception {
        onEdt(() -> {
            tabs.dispatchEvent(mouse(MouseEvent.MOUSE_MOVED, tabs.tabBounds(Tab.BOOKMARKS).x + 2, 1));
            tabs.dispatchEvent(mouse(MouseEvent.MOUSE_EXITED, -1, -1));
            assertThat(tabs.active()).isEqualTo(Tab.LOG);
        });
    }

    @Test
    void itPaintsEveryStateWithoutThrowing() throws Exception {
        onEdt(() -> {
            tabs.setMatchCount("12");
            tabs.setBookmarkCount(4);
            for (Tab active : new Tab[]{Tab.LOG, Tab.MATCHES, Tab.BOOKMARKS}) {
                tabs.setActive(active);
                paint();
            }
            tabs.setMatchCount(null);
            paint();
        });
    }

    @Test
    void flashingATabMarksOnlyThatTab() throws Exception {
        onEdt(() -> {
            tabs.flash(Tab.BOOKMARKS);

            assertThat(tabs.isFlashing(Tab.BOOKMARKS)).isTrue();
            assertThat(tabs.isFlashing(Tab.LOG)).isFalse();
            assertThat(tabs.isFlashing(Tab.MATCHES)).isFalse();
            assertThat(tabs.isFlashing(Tab.DETAILS)).isFalse();
        });
    }

    @Test
    void aFlashFillsTheTabInItsAccentAtThePeakAndIsGoneAfterwards() throws Exception {
        onEdt(() -> {
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(5_000);
            ViewTabs flashing = new ViewTabs(new Font(Font.MONOSPACED, Font.PLAIN, 12), clock::get);
            flashing.setSize(flashing.getPreferredSize());
            Rectangle matches = flashing.tabBounds(Tab.MATCHES);
            int x = matches.x + 2;
            int y = matches.height / 2;
            assertThat(alphaAt(flashing, x, y)).as("at rest").isZero();

            flashing.flash(Tab.MATCHES);
            clock.addAndGet(AttentionFlash.POP_MILLIS);
            java.awt.image.BufferedImage peak = render(flashing);
            int pixel = peak.getRGB(x, y);
            assertThat(pixel >>> 24).as("filled at the peak").isGreaterThan(200);
            assertThat((pixel >> 16) & 0xff).as("in the amber accent").isGreaterThan(pixel & 0xff);
            assertThat(peak.getRGB(matches.x - 2, y) >>> 24).as("the glare reaches the neighbour").isGreaterThan(0);

            clock.addAndGet(AttentionFlash.DURATION_MILLIS);
            assertThat(flashing.isFlashing(Tab.MATCHES)).isFalse();
            assertThat(alphaAt(flashing, x, y)).as("faded out").isZero();
        });
    }

    @Test
    void reflashingRestartsTheFlash() throws Exception {
        onEdt(() -> {
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
            ViewTabs flashing = new ViewTabs(new Font(Font.MONOSPACED, Font.PLAIN, 12), clock::get);
            flashing.setSize(flashing.getPreferredSize());
            flashing.flash(Tab.MATCHES);
            clock.addAndGet(AttentionFlash.DURATION_MILLIS - 10);
            assertThat(flashing.isFlashing(Tab.MATCHES)).isTrue();

            flashing.flash(Tab.MATCHES);
            clock.addAndGet(100);

            assertThat(flashing.isFlashing(Tab.MATCHES)).isTrue();
        });
    }

    private static int alphaAt(ViewTabs component, int x, int y) {
        return render(component).getRGB(x, y) >>> 24;
    }

    private static java.awt.image.BufferedImage render(ViewTabs component) {
        var image = new java.awt.image.BufferedImage(component.getWidth(), component.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            component.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private void paint() {
        var image = new java.awt.image.BufferedImage(tabs.getWidth(), tabs.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            tabs.paint(g);
        } finally {
            g.dispose();
        }
    }

    private String tooltip(Tab tab) {
        return tabs.getToolTipText(mouse(MouseEvent.MOUSE_MOVED, tabs.tabBounds(tab).x + 2, 1));
    }

    private void click(int x) {
        tabs.dispatchEvent(mouse(MouseEvent.MOUSE_CLICKED, x, 1));
    }

    private MouseEvent mouse(int id, int x, int y) {
        return new MouseEvent(tabs, id, 0, id == MouseEvent.MOUSE_CLICKED ? InputEvent.BUTTON1_DOWN_MASK : 0, x, y,
                id == MouseEvent.MOUSE_CLICKED ? 1 : 0, false,
                id == MouseEvent.MOUSE_CLICKED ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
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
