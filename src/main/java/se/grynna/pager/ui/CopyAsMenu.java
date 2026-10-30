package se.grynna.pager.ui;

import com.formdev.flatlaf.FlatClientProperties;
import se.grynna.pager.export.ExportFormat;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * The format list of Copy as…: a header with the event count, then one numbered item per {@link ExportFormat}
 * with its description beside the name. Built and placed apart from showing it, so it runs headless.
 */
public final class CopyAsMenu {

    /** Narrowest the popup gets, so the descriptions have room beside the names. */
    static final int MIN_WIDTH = 300;

    private static final Insets ITEM_MARGIN = new Insets(4, 12, 4, 12);

    private CopyAsMenu() {}

    /**
     * One numbered item per {@link ExportFormat} (keys 1–6, the digit underlined); choosing one passes it on.
     * The header names the number of events the copy takes.
     */
    public static JPopupMenu build(int eventCount, Consumer<ExportFormat> onChoice) {
        JPopupMenu menu = new JPopupMenu();
        JLabel header = new JLabel(header(eventCount));
        header.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");
        header.setForeground(DarculaColors.G7);
        header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        menu.add(header);
        menu.addSeparator();

        ExportFormat[] formats = ExportFormat.values();
        for (int i = 0; i < formats.length; i++) {
            ExportFormat format = formats[i];
            JMenuItem item = new JMenuItem(itemText(i + 1, format));
            item.setMnemonic(KeyEvent.VK_1 + i);
            item.setMargin(ITEM_MARGIN);
            item.addActionListener(e -> onChoice.accept(format));
            menu.add(item);
        }

        Dimension preferred = menu.getPreferredSize();
        menu.setPreferredSize(new Dimension(Math.max(MIN_WIDTH, preferred.width), preferred.height));
        return menu;
    }

    static String header(int eventCount) {
        return "Copy " + eventCount + (eventCount == 1 ? " event as" : " events as");
    }

    static String itemText(int key, ExportFormat format) {
        return String.format("<html><u>%d</u>&nbsp;&nbsp;<b>%s</b>&nbsp;&nbsp;<font color='#%06x'>%s</font></html>",
                key, format.displayName(), DarculaColors.G7.getRGB() & 0xFFFFFF, format.description());
    }

    /**
     * Top-left corner of the popup in the viewer's coordinates, just below the cursor row and starting at the
     * message text ({@code textX}). Above the row when the popup does not fit below it; always clamped so the
     * whole popup stays inside the viewer.
     *
     * @param rowY      top edge of the cursor row
     * @param rowHeight height of one row
     * @param textX     where the message text starts
     * @param popup     the popup's size
     * @param viewer    the viewer's size
     */
    static Point placement(int rowY, int rowHeight, int textX, Dimension popup, Dimension viewer) {
        int below = rowY + rowHeight;
        int y = below;
        if (below + popup.height > viewer.height && rowY - popup.height >= 0) {
            y = rowY - popup.height;
        }
        return new Point(clamp(textX, viewer.width, popup.width), clamp(y, viewer.height, popup.height));
    }

    /** Centre of the viewer, the popup's corner when there is no cursor row to attach it to. */
    static Point centred(Dimension popup, Dimension viewer) {
        return new Point(clamp((viewer.width - popup.width) / 2, viewer.width, popup.width),
                clamp((viewer.height - popup.height) / 2, viewer.height, popup.height));
    }

    /** {@code value} kept so a span of {@code size} starting there ends inside {@code space}. */
    private static int clamp(int value, int space, int size) {
        return Math.max(0, Math.min(value, space - size));
    }
}
