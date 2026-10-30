package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.export.ExportFormat;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopyAsMenuTest {

    private static final Dimension POPUP = new Dimension(300, 120);
    private static final Dimension VIEWER = new Dimension(800, 600);

    @Test
    void headerNamesTheEventCount() {
        assertThat(headerText(CopyAsMenu.build(1, format -> {}))).isEqualTo("Copy 1 event as");
        assertThat(headerText(CopyAsMenu.build(3, format -> {}))).isEqualTo("Copy 3 events as");
    }

    @Test
    void listsEveryFormatWithItsNameAndDescriptionInOrder() {
        JPopupMenu menu = CopyAsMenu.build(1, format -> {});

        List<JMenuItem> items = items(menu);
        assertThat(items).hasSize(ExportFormat.values().length);
        for (int i = 0; i < items.size(); i++) {
            ExportFormat format = ExportFormat.values()[i];
            assertThat(items.get(i).getText())
                    .contains(">" + format.displayName() + "<")
                    .contains(format.description());
        }
        assertThat(CopyAsMenu.itemText(1, ExportFormat.TEXT)).contains("As shown", "time + message, like Ctrl-C");
        assertThat(CopyAsMenu.itemText(5, ExportFormat.CSV)).contains("CSV", "opens in Excel");
    }

    @Test
    void itemsHaveDigitMnemonicsOneToSix() {
        JPopupMenu menu = CopyAsMenu.build(1, format -> {});

        List<JMenuItem> items = items(menu);
        for (int i = 0; i < items.size(); i++) {
            assertThat(items.get(i).getMnemonic()).isEqualTo(KeyEvent.VK_1 + i);
        }
        assertThat(CopyAsMenu.itemText(1, ExportFormat.TEXT)).contains("<u>1</u>");
    }

    @Test
    void menuIsAtLeastThreeHundredPixelsWide() {
        assertThat(CopyAsMenu.build(1, format -> {}).getPreferredSize().width).isGreaterThanOrEqualTo(300);
    }

    @Test
    void choosingAnItemPassesItsFormatOn() {
        List<ExportFormat> chosen = new ArrayList<>();
        JPopupMenu menu = CopyAsMenu.build(2, chosen::add);

        items(menu).get(ExportFormat.CSV.ordinal()).doClick();
        items(menu).get(ExportFormat.HTML.ordinal()).doClick();

        assertThat(chosen).containsExactly(ExportFormat.CSV, ExportFormat.HTML);
    }

    @Test
    void placementGoesBelowTheCursorRowAtTheTextColumn() {
        Point at = CopyAsMenu.placement(40, 16, 100, POPUP, VIEWER);

        assertThat(at).isEqualTo(new Point(100, 56));
    }

    @Test
    void placementFlipsAboveTheRowWhenItDoesNotFitBelow() {
        Point at = CopyAsMenu.placement(580, 16, 100, POPUP, VIEWER);

        assertThat(at).isEqualTo(new Point(100, 460));
    }

    @Test
    void placementClampsRightSoThePopupStaysInside() {
        Point at = CopyAsMenu.placement(40, 16, 700, POPUP, VIEWER);

        assertThat(at).isEqualTo(new Point(500, 56));
    }

    @Test
    void placementClampsLeftAndBottomWhenNeitherSideFits() {
        assertThat(CopyAsMenu.placement(40, 16, -20, POPUP, VIEWER).x).isZero();
        assertThat(CopyAsMenu.placement(10, 16, 100, POPUP, new Dimension(800, 100)))
                .isEqualTo(new Point(100, 0));
    }

    @Test
    void placementWithoutCursorIsCentred() {
        assertThat(CopyAsMenu.centred(POPUP, VIEWER)).isEqualTo(new Point(250, 240));
        assertThat(CopyAsMenu.centred(new Dimension(900, 700), VIEWER)).isEqualTo(new Point(0, 0));
    }

    private static String headerText(JPopupMenu menu) {
        return ((JLabel) menu.getComponent(0)).getText();
    }

    private static List<JMenuItem> items(JPopupMenu menu) {
        List<JMenuItem> items = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item) {
                items.add(item);
            }
        }
        return items;
    }
}
