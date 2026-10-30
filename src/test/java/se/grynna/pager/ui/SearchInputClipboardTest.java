package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.clip.InMemoryClipboard;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The search input is a plain text field for Copy, Cut, Paste and Select all, whatever the look and
 * feel binds; paste always lands as one line. The system clipboard is replaced by an in-memory one.
 */
class SearchInputClipboardTest {

    private StatusBar statusBar;
    private InMemoryClipboard clipboard;

    @BeforeEach
    void setUp() throws Exception {
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        clipboard = new InMemoryClipboard();
        onEdt(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer);
            statusBar.setClipboard(clipboard);
            statusBar.inputField().setEditable(true);
        });
    }

    @Test
    void copyTakesTheSelectedTextOfTheField() throws Exception {
        onEdt(() -> {
            statusBar.inputField().setText("timeout");
            statusBar.inputField().selectAll();
            run(DefaultEditorKit.copyAction);

            assertThat(clipboard.text()).contains("timeout");
            assertThat(statusBar.inputField().getText()).isEqualTo("timeout");
        });
    }

    @Test
    void cutRemovesTheSelectionAndKeepsItOnTheClipboard() throws Exception {
        onEdt(() -> {
            statusBar.inputField().setText("timeout");
            statusBar.inputField().select(0, 3);
            run(DefaultEditorKit.cutAction);

            assertThat(clipboard.text()).contains("tim");
            assertThat(statusBar.inputField().getText()).isEqualTo("eout");
        });
    }

    @Test
    void pasteOfAMultiLineTextBecomesOneLine() throws Exception {
        clipboard.setText("first line\nsecond line\n");

        onEdt(() -> {
            statusBar.inputField().setText("");
            run(DefaultEditorKit.pasteAction);

            assertThat(statusBar.inputField().getText()).isEqualTo("first line second line");
        });
    }

    @Test
    void pasteReplacesTheSelection() throws Exception {
        clipboard.setText("error\n");

        onEdt(() -> {
            statusBar.inputField().setText("timeout");
            statusBar.inputField().selectAll();
            run(DefaultEditorKit.pasteAction);

            assertThat(statusBar.inputField().getText()).isEqualTo("error");
        });
    }

    @Test
    void pasteWithoutClipboardTextChangesNothing() throws Exception {
        onEdt(() -> {
            statusBar.inputField().setText("timeout");
            run(DefaultEditorKit.pasteAction);

            assertThat(statusBar.inputField().getText()).isEqualTo("timeout");
        });
    }

    @Test
    void copyWithoutASelectionLeavesTheClipboardAlone() throws Exception {
        clipboard.setText("kept");

        onEdt(() -> {
            statusBar.inputField().setText("timeout");
            statusBar.inputField().setCaretPosition(3);
            run(DefaultEditorKit.copyAction);
        });

        assertThat(clipboard.text()).contains("kept");
    }

    @Test
    void theStandardKeysAreBoundInTheFieldItself() throws Exception {
        onEdt(() -> {
            InputMap im = statusBar.inputField().getInputMap(JComponent.WHEN_FOCUSED);
            int ctrl = InputEvent.CTRL_DOWN_MASK;
            int shift = InputEvent.SHIFT_DOWN_MASK;

            assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_C, ctrl))).isEqualTo(DefaultEditorKit.copyAction);
            assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, ctrl))).isEqualTo(DefaultEditorKit.copyAction);
            assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_X, ctrl))).isEqualTo(DefaultEditorKit.cutAction);
            assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, shift))).isEqualTo(DefaultEditorKit.cutAction);
            assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_V, ctrl))).isEqualTo(DefaultEditorKit.pasteAction);
            assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, shift)))
                    .isEqualTo(DefaultEditorKit.pasteAction);
            assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_A, ctrl)))
                    .isEqualTo(DefaultEditorKit.selectAllAction);
            assertThat(statusBar.inputField().getActionMap().get(DefaultEditorKit.pasteAction)).isNotNull();
        });
    }

    private void run(String actionName) {
        statusBar.inputField().getActionMap().get(actionName)
                .actionPerformed(new ActionEvent(statusBar.inputField(), ActionEvent.ACTION_PERFORMED, actionName));
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
