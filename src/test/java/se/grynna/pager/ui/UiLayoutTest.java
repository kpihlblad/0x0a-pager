package se.grynna.pager.ui;

import com.formdev.flatlaf.FlatClientProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.preferences.PreferencesPanel;
import se.grynna.pager.ui.time.TimePanel;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@link UiLayout} preference switches between {@link UiLayout#MOST} (status and prompt rows docked at the bottom)
 * and {@link UiLayout#WIN} (status row merged into the window title bar at the top, with the prompt/action row below it
 * and the time panel docked to the top of the log).
 */
class UiLayoutTest {

    private static final Instant T = Instant.parse("2017-05-01T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        new Config().setUiLayout(UiLayout.MOST);
        SwingUtilities.invokeAndWait(PagerLaf::setup);
    }

    @AfterEach
    void tearDown() {
        new Config().setUiLayout(UiLayout.MOST);
        JFrame.setDefaultLookAndFeelDecorated(false);
    }

    @Test
    void mostAndWinLayoutsPlaceTheBarsAndConfigureTheMergedTitleBarAndTimePanel() throws Exception {
        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(mock(SplunkLogDataProvider.class));

        SwingUtilities.invokeAndWait(() -> {
            JRootPane rootPane = new JRootPane();
            LogViewer viewer = new LogViewer(Clock.systemUTC());
            rootPane.getContentPane().add(viewer);
            rootPane.setSize(1024, 768);
            layOut(rootPane);
            viewer.loginComplete(searchManager);

            BorderLayout layout = (BorderLayout) viewer.getLayout();
            StatusBar statusBar = find(viewer, StatusBar.class).getFirst();
            assertThat(viewer.uiLayout()).isEqualTo(UiLayout.MOST);
            assertThat(layout.getLayoutComponent(BorderLayout.SOUTH)).isSameAs(statusBar);
            assertThat(layout.getLayoutComponent(BorderLayout.NORTH)).isNull();
            assertThat(Boolean.TRUE.equals(rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT)))
                    .isFalse();

            viewer.applyUiLayout(UiLayout.WIN);
            layOut(rootPane);

            assertThat(viewer.uiLayout()).isEqualTo(UiLayout.WIN);
            assertThat(layout.getLayoutComponent(BorderLayout.NORTH)).isSameAs(statusBar);
            assertThat(layout.getLayoutComponent(BorderLayout.SOUTH)).isNull();
            assertThat(rootPane.getClientProperty(FlatClientProperties.USE_WINDOW_DECORATIONS)).isEqualTo(Boolean.TRUE);
            assertThat(rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT)).isEqualTo(Boolean.TRUE);
            assertThat(rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON)).isEqualTo(Boolean.FALSE);
            assertThat(rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE)).isEqualTo(Boolean.FALSE);
            assertThat(rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND))
                    .isEqualTo(DarculaColors.MIDNIGHT_BLUE);

            // View mode changes update the merged title bar's background so the window buttons match the status row.
            statusBar.setMatchesMode(true);
            assertThat(rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND))
                    .isEqualTo(DarculaColors.MATCHES_BAR);
            statusBar.setBookmarksMode(true);
            assertThat(rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND))
                    .isEqualTo(DarculaColors.BOOKMARKS_BAR);
            statusBar.setBookmarksMode(false);
            statusBar.setMatchesMode(false);
            assertThat(rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND))
                    .isEqualTo(DarculaColors.MIDNIGHT_BLUE);

            // In WIN layout, the time panel docks to the top of the log (y == 0) right below the prompt/action bar,
            // and tooltips on the top bars open below their controls to stay inside the window.
            statusBar.setBrowsingRange(T.minusSeconds(3600), T);
            viewer.openTimePanel(TimePanel.Focus.AT);
            TimePanel timePanel = find(viewer, TimePanel.class).getFirst();
            assertThat(timePanel.getY()).isEqualTo(0);

            JButton scopeButton = find(statusBar, JButton.class).stream()
                    .filter(b -> "Scope: index and filters (L)".equals(b.getToolTipText()))
                    .findFirst().orElseThrow();
            Point tipAtTop = scopeButton.getToolTipLocation(new MouseEvent(scopeButton, MouseEvent.MOUSE_MOVED, 0, 0,
                    scopeButton.getWidth() / 2, scopeButton.getHeight() / 2, 0, false));
            assertThat(tipAtTop).isNotNull();
            assertThat(tipAtTop.y).isGreaterThanOrEqualTo(scopeButton.getHeight());

            // Switching back to MOST docks the bars and the open time panel at the bottom again.
            viewer.applyUiLayout(UiLayout.MOST);
            layOut(rootPane);
            assertThat(viewer.uiLayout()).isEqualTo(UiLayout.MOST);
            assertThat(layout.getLayoutComponent(BorderLayout.SOUTH)).isSameAs(statusBar);
            assertThat(layout.getLayoutComponent(BorderLayout.NORTH)).isNull();
            assertThat(Boolean.TRUE.equals(rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT)))
                    .isFalse();
            JLayeredPane layers = (JLayeredPane) timePanel.getParent();
            assertThat(timePanel.getY() + timePanel.getHeight()).isEqualTo(layers.getHeight());
        });
    }

    @Test
    void preferencesPanelExposesAndSavesTheUiLayoutAndThemeChoices() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Config config = new Config();
            config.setUiLayout(UiLayout.MOST);
            config.setTheme(se.grynna.pager.utils.Theme.DARK);
            boolean[] closed = {false};
            PreferencesPanel panel = new PreferencesPanel(config, saved -> closed[0] = saved);
            JToggleButton winToggle = find(panel, JToggleButton.class).stream()
                    .filter(b -> UiLayout.WIN.displayName().equals(b.getText()))
                    .findFirst().orElseThrow();
            JToggleButton mostToggle = find(panel, JToggleButton.class).stream()
                    .filter(b -> UiLayout.MOST.displayName().equals(b.getText()))
                    .findFirst().orElseThrow();
            JToggleButton lightToggle = find(panel, JToggleButton.class).stream()
                    .filter(b -> "light".equals(b.getText()))
                    .findFirst().orElseThrow();

            assertThat(mostToggle.isSelected()).isTrue();
            assertThat(winToggle.isSelected()).isFalse();
            assertThat(lightToggle.isSelected()).isFalse();

            winToggle.doClick();
            lightToggle.doClick();

            JButton save = find(panel, JButton.class).stream()
                    .filter(b -> "save".equals(b.getText()))
                    .findFirst().orElseThrow();
            save.doClick();

            assertThat(closed[0]).isTrue();
            assertThat(config.getUiLayout()).isEqualTo(UiLayout.WIN);
            assertThat(config.getTheme()).isEqualTo(se.grynna.pager.utils.Theme.LIGHT);
            config.setTheme(se.grynna.pager.utils.Theme.DARK);
        });
    }

    private static void layOut(Container container) {
        container.doLayout();
        for (Component c : container.getComponents()) {
            if (c instanceof Container child) {
                layOut(child);
            }
        }
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
