package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.export.ExportFormat;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.clip.InMemoryClipboard;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Copy as… through the viewer, with no window shown: the cursor's event, a selection of events, and
 * the in-memory clipboard. The log has five rows of four events: row 0 starts event 0 (rows 0-2),
 * row 3 is event 1, row 4 is event 2.
 */
class LogViewerCopyAsTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int[] EVENT_OF_ROW = {0, 0, 0, 1, 2};
    private static final boolean[] CONTINUATION = {false, true, true, false, false};

    private LogViewer viewer;
    private StatusBar statusBar;
    private final InMemoryClipboard clipboard = new InMemoryClipboard();

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider logProvider = mock(SplunkLogDataProvider.class);
        when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, T0.plusSeconds(600)));
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T0.plusSeconds(600)));
        when(logProvider.getStatistics()).thenReturn(new LogStatistics(LogStatistics.Status.LOADED,
                EVENT_OF_ROW.length, T0, T0.plusSeconds(600)));
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> line(inv.getArgument(0)));
        when(logProvider.peekLine(anyInt())).thenAnswer(inv -> Optional.of(line(inv.getArgument(0))));

        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(logProvider);
        when(searchManager.scope()).thenReturn(Scope.of("web", Map.of()));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
            viewer.cursorManager().updateSize(50, 200);
            viewer.cursorManager().setClipboard(clipboard);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
    }

    @Test
    void copyAsCsvWritesTheCursorsEventWholeAndNoticesIt() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(1);

            viewer.copyAs(ExportFormat.CSV);

            String text = clipboard.text().orElseThrow();
            assertThat(text).startsWith("time,level,message\r\n").doesNotStartWith("\uFEFF");
            assertThat(text).contains("\"message 0\nmessage 1\nmessage 2\"").doesNotContain("message 3");
            assertThat(statusBar.noticeText()).isEqualTo("Copied 1 event as CSV");
        });
    }

    @Test
    void copyAsHtmlOffersTheHtmlAndThePlainTextOfTheSelectedEvents() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            viewer.cursorManager().setMarkAtCursor();
            viewer.cursorManager().moveCursor(1, 3, true);

            viewer.copyAs(ExportFormat.HTML);

            assertThat(clipboard.contents().isDataFlavorSupported(DataFlavor.allHtmlFlavor)).isTrue();
            String html = (String) clipboard.contents().getTransferData(DataFlavor.allHtmlFlavor);
            assertThat(html).contains("message 3").contains("message 4").startsWith("<table>");
            assertThat(clipboard.text().orElseThrow()).contains("message 3").contains("message 4")
                    .doesNotContain("<table>");
            assertThat(statusBar.noticeText()).isEqualTo("Copied 2 events as HTML table");
        });
    }

    @Test
    void withoutASelectionOrACursorThereIsNothingToCopy() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().hideCursor();
            assertThat(viewer.cursorManager().cursorLine()).isEmpty();

            viewer.copyAs(ExportFormat.TEXT);

            assertThat(clipboard.contents()).isNull();
            assertThat(statusBar.noticeText()).isEqualTo("Nothing to copy");
        });
    }

    private static LoadedLogLine line(int row) {
        int event = row >= 0 && row < EVENT_OF_ROW.length ? EVENT_OF_ROW[row] : row;
        boolean continuation = row >= 0 && row < CONTINUATION.length && CONTINUATION[row];
        Instant time = T0.plusSeconds(10L * event);
        var line = new LoadedLogLine(MsgType.JSON, "seg", row, "cb" + event, "bkt", time, "raw", time, time,
                "message " + row, continuation, Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cb" + event, "_bkt", "bkt")));
        return line;
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
