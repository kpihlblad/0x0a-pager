package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.preferences.PreferencesPanel;

import javax.swing.*;
import java.awt.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preferences overlay is resized with the layered pane, so its form and
 * Save/Cancel buttons stay reachable when the window shrinks or grows.
 */
class PreferencesOverlayResizeTest {

    private LogViewer viewer;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1600, 1200);
            viewer.doLayout();
        });
        drainEdt();
        onEdt(() -> viewer.showPreferences());
        drainEdt();
        onEdt(() -> layOut(viewer));
    }

    @Test
    void overlayFollowsTheViewerAreaAndKeepsSaveAndCancelReachable() throws Exception {
        assertOverlayCoversViewerAndButtonsReachable();

        resize(700, 600);
        assertOverlayCoversViewerAndButtonsReachable();

        resize(1800, 1300);
        assertOverlayCoversViewerAndButtonsReachable();
    }

    private void resize(int width, int height) throws Exception {
        onEdt(() -> {
            viewer.setSize(width, height);
            viewer.doLayout();
        });
        // The layered pane's resize listener runs from the event queue
        drainEdt();
        onEdt(() -> layOut(viewer));
    }

    /** Lays out the whole tree; without a window (no peers) validate() is a no-op. */
    private static void layOut(Container container) {
        container.doLayout();
        for (Component c : container.getComponents()) {
            if (c instanceof Container child) {
                layOut(child);
            }
        }
    }

    private void assertOverlayCoversViewerAndButtonsReachable() throws Exception {
        onEdt(() -> {
            JLayeredPane layeredPane = find(viewer, JLayeredPane.class).getFirst();
            PreferencesPanel panel = find(viewer, PreferencesPanel.class).getFirst();
            Container overlay = panel.getParent();
            Rectangle area = new Rectangle(0, 0, layeredPane.getWidth(), layeredPane.getHeight());

            assertThat(overlay.getParent()).isSameAs(layeredPane);
            assertThat(overlay.getBounds()).isEqualTo(area);

            for (String label : List.of("save", "cancel")) {
                JButton button = find(panel, JButton.class).stream().filter(b -> label.equals(b.getText())).findFirst()
                        .orElseThrow(() -> new AssertionError(label + " button missing"));
                Rectangle inPane = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), layeredPane);
                assertThat(inPane.isEmpty()).as(label + " laid out").isFalse();
                assertThat(area.contains(inPane)).as(label + " %s inside %s", inPane, area).isTrue();
            }
        });
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                found.add(type.cast(c));
            }
            if (c instanceof Container container) {
                found.addAll(find(container, type));
            }
        }
        return found;
    }

    private static void drainEdt() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }
}
