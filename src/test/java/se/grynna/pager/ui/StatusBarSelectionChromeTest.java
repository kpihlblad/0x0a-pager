package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.ActionMap;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Narrow and Exclude in the prompt row act on a one-line selection when there is one. */
class StatusBarSelectionChromeTest {

    private LogViewer viewer;
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.CURSOR);
        onEdt(() -> {
            PagerLaf.setup();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer);
            statusBar.setSearchInputActive(true);
            statusBar.setSearchInputActive(false);
        });
    }

    @Test
    void selectionShowsCompactActionsAndRoutesClicksToTheSelection() throws Exception {
        onEdt(() -> {
            assertThat(button("Search").isVisible()).isFalse();
            assertThat(button("+ Narrow").isVisible()).isFalse();
            assertThat(button("− Exclude").isVisible()).isFalse();

            statusBar.setSelectionTerm("timeout");

            assertThat(button("Search").isVisible()).isTrue();
            assertThat(button("+ Narrow").isVisible()).isTrue();
            assertThat(button("− Exclude").isVisible()).isTrue();
            assertThat(button("Search").getToolTipText()).isEqualTo("Search for selection \"timeout\"");
            assertThat(button("+ Narrow").getToolTipText()).isEqualTo("Narrow to selection \"timeout\"");
            assertThat(button("− Exclude").getToolTipText()).isEqualTo("Exclude selection \"timeout\"");
            assertThat(button("Search").getForeground()).isEqualTo(DarculaColors.SEARCH_ACCENT);
            assertThat(button("+ Narrow").getForeground()).isEqualTo(DarculaColors.NARROW_ACCENT);
            assertThat(button("− Exclude").getForeground()).isEqualTo(DarculaColors.EXCLUDE_ACCENT);
            List<Component> components = flatten(statusBar);
            assertThat(components.indexOf(button("Search"))).isLessThan(components.indexOf(button("+ Narrow")));
            assertThat(components.indexOf(button("+ Narrow"))).isLessThan(components.indexOf(button("− Exclude")));
            assertThat(List.of(button("Search"), button("+ Narrow"), button("− Exclude")))
                    .allMatch(ActionPill.class::isInstance)
                    .allSatisfy(button -> {
                        ActionPill pill = (ActionPill) button;
                        assertThat(pill.accent()).isEqualTo(pill.getForeground());
                        assertThat(pill.isContentAreaFilled()).isFalse();
                        assertThat(pill.isBorderPainted()).isFalse();
                        assertThat(pill.getMargin()).isEqualTo(new Insets(0, 4, 0, 4));
                        assertRoundedCapsule(pill);
                    });

            button("Search").doClick();
            button("+ Narrow").doClick();
            button("− Exclude").doClick();
        });

        verify(viewer).searchForSelection();
        verify(viewer).narrowScopeToSelection();
        verify(viewer).excludeSelectionFromScope();
        verify(viewer, never()).narrowScopeToSearch();
        verify(viewer, never()).excludeSearchFromScope();
    }

    @Test
    void clearingTheSelectionRestoresTheActiveSearchActions() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "search term");
            statusBar.setSelectionTerm("selection");
            statusBar.setSelectionTerm(null);

            assertThat(button("Search").isVisible()).isFalse();
            assertThat(button("+ Narrow").getToolTipText()).isEqualTo("Narrow scope to matches (+)");
            button("+ Narrow").doClick();
            button("− Exclude").doClick();
        });

        verify(viewer).narrowScopeToSearch();
        verify(viewer).excludeSearchFromScope();
        verify(viewer, never()).searchForSelection();
        verify(viewer, never()).narrowScopeToSelection();
        verify(viewer, never()).excludeSelectionFromScope();
    }

    @Test
    void selectionActionsHideWhenThePromptRowIsTooNarrow() throws Exception {
        onEdt(() -> {
            statusBar.setSize(1600, 100);
            layout(statusBar);
            statusBar.setSelectionTerm("timeout");
            assertThat(button("Search").isVisible()).isTrue();
            assertThat(button("+ Narrow").isVisible()).isTrue();

            statusBar.setSize(200, 100);
            layout(statusBar);
            statusBar.setSelectionTerm("different selection");
            assertThat(button("Search").isVisible()).isFalse();
            assertThat(button("+ Narrow").isVisible()).isFalse();
            assertThat(button("− Exclude").isVisible()).isFalse();
        });
    }

    @Test
    void actionPillsMatchTheOtherChromeButtonsInHeight() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "search term");
            statusBar.setSelectionTerm("timeout");
            statusBar.setSize(1600, 100);
            layout(statusBar);

            int pillHeight = button("Search").getPreferredSize().height;
            assertThat(button("+ Narrow").getPreferredSize().height).isEqualTo(pillHeight);
            assertThat(button("− Exclude").getPreferredSize().height).isEqualTo(pillHeight);
            assertThat(buttonByTooltip("Exit search (Esc)").getPreferredSize().height).isEqualTo(pillHeight);
            assertThat(buttonByTooltip("Stop search").getPreferredSize().height).isEqualTo(pillHeight);
        });
    }

    @Test
    void actionPillsKeepTheWidthTheirLabelNeeds() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "search term");
            statusBar.setSelectionTerm("timeout");
            statusBar.setSize(1600, 100);
            layout(statusBar);

            for (String text : List.of("Search", "+ Narrow", "\u2212 Exclude")) {
                JButton pill = button(text);
                assertThat(pill.getMaximumSize().width).isEqualTo(pill.getPreferredSize().width);
            }
        });
    }

    @Test
    void promptsAndDetailsHideTheSelectionActions() throws Exception {
        onEdt(() -> {
            statusBar.setSelectionTerm("timeout");
            statusBar.setSearchInputActive(true);
            assertThat(button("Search").isVisible()).isFalse();
            assertThat(button("+ Narrow").isVisible()).isFalse();
            assertThat(button("− Exclude").isVisible()).isFalse();

            statusBar.setSearchInputActive(false);
            assertThat(button("Search").isVisible()).isTrue();
            assertThat(button("+ Narrow").isVisible()).isTrue();
            statusBar.showScopeConfirmPrompt("Continue?");
            assertThat(button("Search").isVisible()).isFalse();
            assertThat(button("+ Narrow").isVisible()).isFalse();
            assertThat(button("− Exclude").isVisible()).isFalse();

            statusBar.clearScopeConfirmPrompt();
            assertThat(button("Search").isVisible()).isTrue();
            assertThat(button("+ Narrow").isVisible()).isTrue();
            statusBar.setDetailsMode(true);
            assertThat(button("Search").isVisible()).isFalse();
            assertThat(button("+ Narrow").isVisible()).isTrue();
            assertThat(button("− Exclude").isVisible()).isTrue();
            assertThat(button("+ Narrow").getToolTipText()).isEqualTo("Narrow scope to the field's value (+)");
        });
    }

    private JButton button(String text) {
        return flatten(statusBar).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static void assertRoundedCapsule(ActionPill pill) {
        int w = Math.max(48, pill.getPreferredSize().width);
        int h = Math.max(20, pill.getPreferredSize().height);
        pill.setSize(w, h);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        pill.paint(g);
        g.dispose();

        assertThat(image.getRGB(0, 0) >>> 24).isZero();
        assertThat(image.getRGB(w - 1, 0) >>> 24).isZero();
        assertThat(image.getRGB(0, h - 1) >>> 24).isZero();
        assertThat(image.getRGB(w - 1, h - 1) >>> 24).isZero();
        assertThat(new Color(image.getRGB(w / 2, 0), true).getRGB()).isEqualTo(pill.accent().getRGB());
        assertThat(new Color(image.getRGB(w / 2, h - 1), true).getRGB()).isEqualTo(pill.accent().getRGB());
    }

    private JButton buttonByTooltip(String tooltip) {
        return flatten(statusBar).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> tooltip.equals(button.getToolTipText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button with tooltip " + tooltip));
    }

    private static List<Component> flatten(Container root) {
        List<Component> all = new ArrayList<>();
        for (Component child : root.getComponents()) {
            all.add(child);
            if (child instanceof Container container) {
                all.addAll(flatten(container));
            }
        }
        return all;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) {
                layout(container);
            }
        }
    }

    private static void onEdt(Runnable action) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(action);
    }
}
