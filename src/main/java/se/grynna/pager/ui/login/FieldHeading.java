package se.grynna.pager.ui.login;

import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * A group heading in the field filter list ({@link FieldGrouping}): the shared name part, e.g.
 * {@code kubernetes}, then {@code pod} one level in. Dim, left-aligned and indented per level, while
 * the rows' labels below are right-aligned against their values.
 */
class FieldHeading extends JPanel {

    private static final int INDENT = 16;
    private final int depth;
    private final String text;

    FieldHeading(int depth, String text) {
        super(new BorderLayout());
        this.depth = depth;
        this.text = text;
        setOpaque(false);
        setBorder(new EmptyBorder(depth == 0 ? 8 : 4, 10 + depth * INDENT, 2, 6));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, ListRow.ROW_HEIGHT));

        JLabel label = new JLabel(text + " \u203A");
        label.setFont(depth == 0 ? Fontalizer.getBoldFont() : Fontalizer.getFont());
        label.setForeground(depth == 0 ? DarculaColors.G8 : DarculaColors.G7);
        add(label, BorderLayout.WEST);
    }

    int depth() {
        return depth;
    }

    String text() {
        return text;
    }
}
