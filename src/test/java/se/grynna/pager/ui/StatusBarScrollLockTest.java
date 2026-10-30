package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The scroll-lock badge in the status bar: shown exactly while cursor mode is off. */
class StatusBarScrollLockTest {

    private LogViewer viewer;
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities
                .invokeAndWait(() -> statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer));
    }

    @Test
    void theBadgeIsShownInPageModeAndHiddenInCursorMode() throws Exception {
        when(viewer.isCursorMode()).thenReturn(false);
        SwingUtilities.invokeAndWait(() -> statusBar.updateState());
        assertThat(statusBar.isScrollLockShown()).isTrue();

        when(viewer.isCursorMode()).thenReturn(true);
        SwingUtilities.invokeAndWait(() -> statusBar.updateState());
        assertThat(statusBar.isScrollLockShown()).isFalse();

        when(viewer.isCursorMode()).thenReturn(false);
        SwingUtilities.invokeAndWait(() -> statusBar.updateState());
        assertThat(statusBar.isScrollLockShown()).isTrue();
    }

    @Test
    void theTooltipNamesTheCursorToggleKey() {
        assertThat(StatusBar.scrollLockTooltip(KeyMap.defaults()))
                .isEqualTo("Scroll lock: arrows scroll the page (F7 toggles)");
        assertThat(StatusBar.scrollLockTooltip(KeyMap.defaults().withFlavour(KeyFlavour.PAGER)))
                .isEqualTo("Scroll lock: arrows scroll the page (v toggles)");
    }
}
