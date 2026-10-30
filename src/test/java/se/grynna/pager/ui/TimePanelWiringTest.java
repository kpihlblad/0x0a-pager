package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.time.TimePanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The time group and the time panel wired into the viewer. */
class TimePanelWiringTest {

    private static final Instant T = Instant.parse("2017-05-01T12:00:00Z");

    @Test
    void aTimeGroupClickOpensTheDockedPanelAndASecondClickClosesIt() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(mock(SplunkLogDataProvider.class));
        SwingUtilities.invokeAndWait(() -> {
            LogViewer viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            viewer.loginComplete(searchManager);
            StatusBar statusBar = (StatusBar) ((BorderLayout) viewer.getLayout())
                    .getLayoutComponent(BorderLayout.SOUTH);
            statusBar.setBrowsingRange(T.minusSeconds(3600), T);
            TimeGroup group = statusBar.centerLabel();
            group.setSize(group.getPreferredSize());

            click(group);
            assertThat(viewer.isTimePanelOpen()).isTrue();
            TimePanel panel = findTimePanel(viewer);
            JLayeredPane layers = (JLayeredPane) panel.getParent();
            assertThat(layers.getLayer(panel)).isEqualTo(JLayeredPane.PALETTE_LAYER);
            assertThat(panel.getWidth()).isEqualTo(layers.getWidth());
            assertThat(panel.getY() + panel.getHeight()).isEqualTo(layers.getHeight());

            click(group);
            assertThat(viewer.isTimePanelOpen()).isFalse();
            assertThat(panel.getParent()).isNull();
        });
    }

    @Test
    void thePanelClosesBeforeItGoesOrSetsTheRange() {
        LogViewPort port = mock(LogViewPort.class);
        TimePanel.Listener listener = LogViewer.timePanelListener(port);

        listener.setRange(T.minusSeconds(60), null);
        listener.go(T);

        InOrder order = inOrder(port);
        order.verify(port).closeTimePanel();
        order.verify(port).setRange(T.minusSeconds(60), null);
        order.verify(port).closeTimePanel();
        order.verify(port).gotoTimestamp(T);
    }

    private static void click(TimeGroup group) {
        Rectangle half = group.halfBounds(TimeGroup.Half.RANGE);
        group.dispatchEvent(new MouseEvent(group, MouseEvent.MOUSE_CLICKED, 0, 0, half.x + half.width / 2, 5, 1,
                false, MouseEvent.BUTTON1));
    }

    private static TimePanel findTimePanel(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof TimePanel panel) {
                return panel;
            }
            if (c instanceof Container container) {
                TimePanel found = findTimePanel(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
