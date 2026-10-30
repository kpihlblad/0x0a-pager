package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.ui.clip.InMemoryClipboard;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Copy in the log and paste into the search input, through the same clipboard seam the application
 * uses: select in the log, Copy, then Paste in the prompt.
 */
class LogCopyIntoSearchInputTest {

    private static final Instant T = Instant.parse("2020-01-01T00:00:00Z");

    private CursorManager cursorManager;
    private StatusBar statusBar;
    private InMemoryClipboard clipboard;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        clipboard = new InMemoryClipboard();

        LogDataProvider provider = mock(LogDataProvider.class);
        when(provider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T));
        when(provider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, T.plusSeconds(60)));
        when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T.plusSeconds(60)));
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> {
            int line = inv.getArgument(0);
            String message = line == 0 ? "alpha" : "beta";
            return new LoadedLogLine(LoadedLogLine.MsgType.JSON, "seg", line, "cb", "bkt", T, "raw", T, T, message,
                    false, Level.INFO);
        });

        cursorManager = new CursorManager(provider, mock(LogViewPort.class));
        cursorManager.updateSize(50, 200);
        cursorManager.setClipboard(clipboard);

        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities.invokeAndWait(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer);
            statusBar.setClipboard(clipboard);
            statusBar.setSearchInputActive(true);
        });
    }

    @Test
    void aSelectedWordCopiedFromTheLogPastesIntoThePrompt() throws Exception {
        int message = DecoratedLogLine.getPreambleLength();
        cursorManager.setCursorFromClick(0, message, false);
        cursorManager.setCursorFromClick(0, message + 5, true);
        cursorManager.copyToClipboard();

        String pasted = paste();

        assertThat(pasted).isEqualTo("alpha");
    }

    @Test
    void aMultiLineSelectionPastesAsOneLine() throws Exception {
        int message = DecoratedLogLine.getPreambleLength();
        cursorManager.setCursorFromClick(0, message, false);
        cursorManager.setCursorFromClick(1, message + 4, true);
        cursorManager.copyToClipboard();

        String pasted = paste();

        assertThat(pasted).doesNotContain("\n").startsWith("alpha").endsWith("beta");
    }

    private String paste() throws Exception {
        String[] result = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = statusBar.inputField();
            field.setText("");
            field.getActionMap().get(DefaultEditorKit.pasteAction)
                    .actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, "paste"));
            result[0] = field.getText();
        });
        return result[0];
    }
}
