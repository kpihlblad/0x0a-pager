package se.grynna.pager.ui;

import javax.swing.JComponent;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

/**
 * Places a component's tooltip inside its containing window, above the component's top edge (or below it when
 * there is no room above) and clamped horizontally to the window's client area. Tooltips on controls at the bottom
 * of the window otherwise appear below the window, where the desktop taskbar or screen edge covers them.
 */
public final class InWindowToolTip {

    private static final int GAP = 4;

    private InWindowToolTip() {}

    /**
     * Top-left corner of a tooltip of size {@code tip} in {@code target}'s own coordinates, centred on {@code mouseX}
     * and placed {@link #GAP} px above {@code target}, or {@link #GAP} px below it when the tooltip does not fit above
     * inside {@code window}. Both axes are clamped so the tooltip stays within {@code window} (also given in
     * {@code target}'s coordinates).
     */
    public static Point location(int mouseX, Dimension target, Dimension tip, Rectangle window) {
        int minX = window.x;
        int maxX = Math.max(minX, window.x + window.width - tip.width);
        int x = Math.clamp(mouseX - tip.width / 2, minX, maxX);

        int above = -tip.height - GAP;
        int below = target.height + GAP;
        int minY = window.y;
        int maxY = Math.max(minY, window.y + window.height - tip.height);
        int y = above >= minY || below > maxY ? above : below;
        return new Point(x, Math.clamp(y, minY, maxY));
    }

    /**
     * Tooltip location for {@code event} on {@code target}, kept inside {@code target}'s root container; {@code null}
     * when {@code target} has no tooltip text at {@code event} or has not been laid out yet.
     */
    public static Point locationFor(JComponent target, MouseEvent event) {
        String text = target.getToolTipText(event);
        if (text == null || text.isEmpty() || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return null;
        }
        Container root = windowBoundsRoot(target);
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            return null;
        }
        JToolTip tip = target.createToolTip();
        tip.setTipText(text);
        Dimension tipSize = tip.getPreferredSize();
        Rectangle window = SwingUtilities.convertRectangle(root, new Rectangle(0, 0, root.getWidth(), root.getHeight()),
                target);
        return location(event.getX(), target.getSize(), tipSize, window);
    }

    private static Container windowBoundsRoot(Component target) {
        if (target instanceof JComponent jc) {
            Container rootPane = jc.getRootPane();
            if (rootPane != null && rootPane.getWidth() > 0 && rootPane.getHeight() > 0) {
                return rootPane;
            }
        }
        Container top = target instanceof Container c ? c : target.getParent();
        while (top != null && top.getParent() != null && top.getParent().getWidth() > 0
                && top.getParent().getHeight() > 0) {
            top = top.getParent();
        }
        return top != null ? top : new Container();
    }
}
