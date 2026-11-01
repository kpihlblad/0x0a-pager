package se.grynna.pager.ui.login;

import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A row of the field filter list: a label column whose text is
 * right-aligned against the value, so a label always sits next to its value however long it is, and the
 * value controls in the centre. Rows are striped and the row under the mouse lights up, which
 * carries the eye from label to value. The list sets one label width for all rows ({@link #setLabelWidth});
 * a label that does not fit is elided from the left, its tooltip keeps the full name.
 */
abstract class ListRow extends JPanel {

    static final int ROW_HEIGHT = 30;
    static final Dimension CHIP_BUTTON_SIZE = new Dimension(20, 22);
    private static final int LABEL_HEIGHT = 24;
    private static final int LABEL_GAP = 6;
    private static final String ELLIPSIS = "…";

    private final JPanel labelColumn;
    private final JLabel nameLabel;
    private final JLabel countLabel;
    private final String fullName;
    private final MouseAdapter hoverTracker;
    private boolean stripe;
    private boolean hover;

    /**
     * @param nameLabel  the label text, elided when it does not fit
     * @param countLabel optional dim text after the name, never elided
     */
    ListRow(JLabel nameLabel, JLabel countLabel) {
        super(new BorderLayout(12, 0));
        this.nameLabel = nameLabel;
        this.countLabel = countLabel;
        this.fullName = nameLabel.getText();
        this.hoverTracker = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setHover(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), ListRow.this);
                setHover(contains(p));
            }
        };
        setOpaque(false);
        setBorder(new EmptyBorder(3, 10, 3, 6));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));

        labelColumn = new JPanel();
        labelColumn.setLayout(new BoxLayout(labelColumn, BoxLayout.X_AXIS));
        labelColumn.setOpaque(false);
        labelColumn.add(Box.createHorizontalGlue());
        labelColumn.add(nameLabel);
        if (countLabel != null) {
            labelColumn.add(Box.createHorizontalStrut(LABEL_GAP));
            labelColumn.add(countLabel);
        }
        labelColumn.setPreferredSize(new Dimension(labelPreferredWidth(), LABEL_HEIGHT));
        add(labelColumn, BorderLayout.WEST);
    }

    /** The width the label column needs to show the whole name and count. */
    int labelPreferredWidth() {
        int width = textWidth(nameLabel, fullName) + insetWidth(nameLabel);
        if (countLabel != null) {
            width += LABEL_GAP + countLabel.getPreferredSize().width;
        }
        return width;
    }

    /** Set the label column width shared by the list; a name that does not fit is elided from the left. */
    void setLabelWidth(int width) {
        labelColumn.setPreferredSize(new Dimension(width, LABEL_HEIGHT));
        int room = width - insetWidth(nameLabel)
                - (countLabel == null ? 0 : LABEL_GAP + countLabel.getPreferredSize().width);
        nameLabel.setText(elideLeft(nameLabel, fullName, room));
        revalidate();
    }

    String labelText() {
        return nameLabel.getText();
    }

    void setStripe(boolean stripe) {
        if (this.stripe != stripe) {
            this.stripe = stripe;
            repaint();
        }
    }

    boolean isStripe() {
        return stripe;
    }

    boolean isHover() {
        return hover;
    }

    /** Light the row while the mouse is over it or any of its children; call after the children are added. */
    void trackHover() {
        trackHover(this);
    }

    void trackHover(Component c) {
        c.removeMouseListener(hoverTracker);
        c.addMouseListener(hoverTracker);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                trackHover(child);
            }
        }
    }

    void setHover(boolean hover) {
        if (this.hover != hover) {
            this.hover = hover;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (hover || stripe) {
            g.setColor(hover ? DarculaColors.LIST_HOVER : DarculaColors.LIST_STRIPE);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        super.paintComponent(g);
    }

    static JButton removeChipButton(String tooltip, Runnable onRemove) {
        JButton button = new JButton("×");
        button.setFont(Fontalizer.getFont());
        button.setForeground(DarculaColors.G7);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setPreferredSize(CHIP_BUTTON_SIZE);
        button.setMaximumSize(CHIP_BUTTON_SIZE);
        button.setMargin(new Insets(0, 2, 0, 2));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(tooltip);
        button.addActionListener(e -> onRemove.run());
        return button;
    }

    /** {@code text}, or {@code …} and as much of its end as fits in {@code room} pixels. */
    static String elideLeft(JLabel label, String text, int room) {
        if (textWidth(label, text) <= room) {
            return text;
        }
        for (int start = 1; start < text.length(); start++) {
            String candidate = ELLIPSIS + text.substring(start);
            if (textWidth(label, candidate) <= room) {
                return candidate;
            }
        }
        return ELLIPSIS;
    }

    private static int textWidth(JLabel label, String text) {
        return label.getFontMetrics(label.getFont()).stringWidth(text);
    }

    private static int insetWidth(JComponent c) {
        Insets in = c.getInsets();
        return in.left + in.right;
    }
}
