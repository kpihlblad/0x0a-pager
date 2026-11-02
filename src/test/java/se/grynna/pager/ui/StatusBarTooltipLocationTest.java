package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.ui.ViewTabs.Tab;

import javax.swing.ActionMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tooltips on the status and search bars are placed above their control and clamped horizontally so they stay inside
 * the containing window instead of hanging below the window's bottom edge.
 */
class StatusBarTooltipLocationTest {

    private static final Instant NOW = Instant.parse("2017-05-01T12:00:00Z");
    private static final Instant T11 = Instant.parse("2017-05-01T11:00:00Z");
    private static final Instant T1145 = Instant.parse("2017-05-01T11:45:00Z");
    private static final Rectangle WINDOW_BOUNDS = new Rectangle(0, 0, 1000, 600);

    private JPanel window;
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        when(viewer.isCursorMode()).thenReturn(false);
        AtomicLong clock = new AtomicLong(1_000);
        onEdt(() -> {
            PagerLaf.setup();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer, clock::get, () -> NOW);
            statusBar.setFirstVisibleTimestamp(T11);
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.setScopeTerms(List.of("one", "two"), List.of("three", "four"));
            window = new JPanel(new BorderLayout());
            JPanel logArea = new JPanel();
            logArea.setPreferredSize(new Dimension(WINDOW_BOUNDS.width, 550));
            window.add(logArea, BorderLayout.CENTER);
            window.add(statusBar, BorderLayout.SOUTH);
            window.setSize(WINDOW_BOUNDS.width, WINDOW_BOUNDS.height);
            layOut(window);
        });
    }

    @Test
    void pureLocationPlacesTooltipAboveAndClampsHorizontallyInsideTheWindow() {
        Dimension target = new Dimension(60, 22);
        Dimension tip = new Dimension(180, 26);

        // Component sits near the bottom-left of an 800x600 window: in target coordinates the window is (-8, -560, 800, 600).
        Point nearLeft = InWindowToolTip.location(10, target, tip, new Rectangle(-8, -560, 800, 600));
        assertThat(nearLeft).isEqualTo(new Point(-8, -30));

        // Component sits near the bottom-right: in target coordinates the window is (-730, -560, 800, 600).
        Point nearRight = InWindowToolTip.location(50, target, tip, new Rectangle(-730, -560, 800, 600));
        assertThat(nearRight).isEqualTo(new Point(70 - tip.width, -30));

        // When there is no room above, it falls back below the component and stays within the window.
        Point nearTop = InWindowToolTip.location(30, target, tip, new Rectangle(-100, -4, 800, 600));
        assertThat(nearTop).isEqualTo(new Point(30 - tip.width / 2, 26));
    }

    @Test
    void searchChromeButtonsKeepTheirTooltipsInsideTheWindowAndAboveTheBar() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "timeout");
            statusBar.updateSearchHitCount(5, false, T11, T1145);
            statusBar.setSelectionTerm("selected");
            layOut(window);

            for (String tooltip : List.of(
                    "Search running",
                    "Stop search",
                    "Exit search (Esc)",
                    "Search for selection \"selected\"",
                    "Narrow to selection \"selected\"",
                    "Exclude selection \"selected\"")) {
                JComponent control = byTooltip(tooltip);
                assertThat(control.isShowing() || control.isVisible()).isTrue();
                assertTooltipStaysInWindowAbove(control, control.getWidth() / 2);
            }
        });
    }

    @Test
    void statusRowControlsKeepTheirTooltipsInsideTheWindowAndAboveTheRow() throws Exception {
        onEdt(() -> {
            ViewTabs tabs = statusBar.viewTabs();
            for (Tab tab : Tab.values()) {
                Rectangle b = tabs.tabBounds(tab);
                assertTooltipStaysInWindowAbove(tabs, b.x + b.width / 2);
            }

            TimeGroup group = statusBar.centerLabel();
            for (TimeGroup.Half half : TimeGroup.Half.values()) {
                Rectangle b = group.halfBounds(half);
                assertTooltipStaysInWindowAbove(group, b.x + b.width / 2);
            }

            for (JComponent chipPart : flatten(statusBar).stream()
                    .filter(c -> c instanceof JLabel || c instanceof JButton)
                    .map(JComponent.class::cast)
                    .filter(c -> c.isVisible() && c.getToolTipText() != null)
                    .toList()) {
                assertTooltipStaysInWindowAbove(chipPart, Math.max(1, chipPart.getWidth() / 2));
            }
        });
    }

    private void assertTooltipStaysInWindowAbove(JComponent control, int mouseX) {
        MouseEvent event = new MouseEvent(control, MouseEvent.MOUSE_MOVED, 0, 0, mouseX, 4, 0, false);
        String text = control.getToolTipText(event);
        assertThat(text).isNotBlank();
        Point loc = control.getToolTipLocation(event);
        assertThat(loc).as("tooltip location for %s (%s)", control.getClass().getSimpleName(), text).isNotNull();

        var tip = control.createToolTip();
        tip.setTipText(text);
        Dimension tipSize = tip.getPreferredSize();
        Point inWindow = SwingUtilities.convertPoint(control, loc, window);
        Point controlInWindow = SwingUtilities.convertPoint(control, new Point(0, 0), window);
        Rectangle tipBounds = new Rectangle(inWindow, tipSize);

        assertThat(WINDOW_BOUNDS.contains(tipBounds))
                .as("tooltip \"%s\" bounds %s must stay inside window %s", text, tipBounds, WINDOW_BOUNDS)
                .isTrue();
        assertThat(tipBounds.y + tipBounds.height)
                .as("tooltip \"%s\" should sit above control top %d", text, controlInWindow.y)
                .isLessThanOrEqualTo(controlInWindow.y);
    }

    private JComponent byTooltip(String tooltip) {
        return flatten(statusBar).stream()
                .filter(JComponent.class::isInstance)
                .map(JComponent.class::cast)
                .filter(c -> tooltip.equals(c.getToolTipText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no component with tooltip " + tooltip));
    }

    private static List<Component> flatten(Container root) {
        List<Component> all = new ArrayList<>();
        for (Component child : root.getComponents()) {
            all.add(child);
            if (child instanceof Container c) {
                all.addAll(flatten(c));
            }
        }
        return all;
    }

    private static void layOut(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container c) {
                layOut(c);
            }
        }
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
