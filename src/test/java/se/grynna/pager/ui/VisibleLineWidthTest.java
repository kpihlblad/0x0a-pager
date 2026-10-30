package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.grynna.pager.splunk.Level.INFO;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.JSON;

/**
 * Every visible line is measured for the horizontal scrollbar, also the lines
 * after the first loaded one that sets the status bar timestamp.
 */
class VisibleLineWidthTest {

    private static final Instant TIMESTAMP = Instant.parse("2017-03-01T10:00:00Z");

    @Test
    void longLoadedMessageEnablesScrollbarWhileStatusBarShowsFirstTimestamp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider provider = mock(SplunkLogDataProvider.class);
        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(provider);

        var before = new Boundary(BoundaryType.BEFORE, TIMESTAMP, 0);
        var after = new Boundary(BoundaryType.AFTER, TIMESTAMP.plusSeconds(100), 100);
        when(provider.getMaxOobPerSide()).thenReturn(3);
        when(provider.getBeforeBoundary()).thenReturn(before);
        when(provider.getAfterBoundary()).thenReturn(after);
        when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, TIMESTAMP.plusSeconds(3600)));
        when(provider.getStatistics())
                .thenReturn(new LogStatistics(LogStatistics.Status.LOADED, 100, TIMESTAMP, TIMESTAMP.plusSeconds(100)));
        String longMessage = "x".repeat(500);
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(invocation -> {
            int lineNo = invocation.getArgument(0);
            if (lineNo < 0) return new BeforeBoundaryLine(lineNo, before);
            if (lineNo >= 100) return new AfterBoundaryLine(lineNo, after);
            var time = TIMESTAMP.plusSeconds(lineNo);
            // The first loaded row is short; a later visible row is long
            String msg = lineNo == 0 ? "short" : longMessage;
            return new LoadedLogLine(JSON, "segment1", lineNo, "cd" + lineNo, "bkt", time, "{msg=\"" + msg + "\"}",
                    time, time, msg, false, INFO);
        });

        LogViewer[] holder = new LogViewer[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new LogViewer(Clock.systemUTC());
            holder[0].setSize(1024, 768);
            holder[0].doLayout();
            holder[0].loginComplete(searchManager);
            // Not displayed: deliver the resize events that size the view panel and scrollbar
            var layeredPane = find(holder[0], JLayeredPane.class).getFirst();
            layeredPane.dispatchEvent(new ComponentEvent(layeredPane, ComponentEvent.COMPONENT_RESIZED));
            var viewPanel = layeredPane.getComponent(layeredPane.getComponentCount() - 1);
            assertThat(viewPanel.getWidth()).isGreaterThan(0);
            viewPanel.dispatchEvent(new ComponentEvent(viewPanel, ComponentEvent.COMPONENT_RESIZED));
        });

        SwingUtilities.invokeAndWait(() -> {
            var viewer = holder[0];
            var statusBar = find(viewer, StatusBar.class).getFirst();
            // The character size is only measured when painting
            viewer.cursorManager().updateSize(20, 100);

            // Boundary placeholder rows above the first loaded row
            try {
                viewer.cursorManager().setPageStart(-2);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            viewer.updateVisibleLines();

            assertThat(statusBar.getFirstVisibleTimestamp()).contains(TIMESTAMP);
            assertThat(viewer.isHorizontalScrollbarVisible()).isTrue();
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
}
