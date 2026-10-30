package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The date field of the status bar shows the selection's time range while the selection covers two or more rows. */
class StatusBarSelectionSpanTest {

    private static final Instant FIRST_VISIBLE = Instant.parse("2026-10-25T12:00:01.200Z");
    private static final Instant LAST_SELECTED = Instant.parse("2026-10-25T12:02:14.600Z");

    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities
                .invokeAndWait(() -> statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer));
    }

    @Test
    void theSelectionRangeReplacesTheFirstVisibleTimeAndClearingRestoresIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> statusBar.setFirstVisibleTimestamp(FIRST_VISIBLE));
        assertThat(dateFieldText()).isEqualTo(DateTimeFormatterManager.formatTimestamp(FIRST_VISIBLE));

        var span = new CursorManager.SelectionSpan(FIRST_VISIBLE, LAST_SELECTED);
        SwingUtilities.invokeAndWait(() -> statusBar.setSelectionSpan(span));
        assertThat(dateFieldText()).contains("\u2192", "(\u0394 ")
                .isEqualTo(DateTimeFormatterManager.formatSelectionSpan(FIRST_VISIBLE, LAST_SELECTED));

        SwingUtilities.invokeAndWait(() -> statusBar.setSelectionSpan(null));
        assertThat(dateFieldText()).isEqualTo(DateTimeFormatterManager.formatTimestamp(FIRST_VISIBLE));
    }

    @Test
    void theSelectionRangeIsKeptWhenTheFirstVisibleTimeChanges() throws Exception {
        var span = new CursorManager.SelectionSpan(FIRST_VISIBLE, LAST_SELECTED);
        SwingUtilities.invokeAndWait(() -> statusBar.setSelectionSpan(span));
        SwingUtilities.invokeAndWait(() -> statusBar.setFirstVisibleTimestamp(LAST_SELECTED));

        assertThat(dateFieldText())
                .isEqualTo(DateTimeFormatterManager.formatSelectionSpan(FIRST_VISIBLE, LAST_SELECTED));
    }

    private String dateFieldText() throws Exception {
        String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> text[0] = statusBar.dateFieldText());
        return text[0];
    }
}
