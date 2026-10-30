package se.grynna.pager.ui.login;

import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.beans.PropertyChangeListener;
import java.util.Map;

/**
 * A ruled section of the scope selector: a small, tracked upper-case SemiBold title, a hairline rule running
 * to the right-hand tools, and the content below. While a component inside the section holds the
 * keyboard focus, an amber bar marks the section's left edge.
 */
public class Section extends JPanel {

    private static final int BAR_WIDTH = 2;
    private static final int CONTENT_INSET = 14;
    private static final float TITLE_SIZE = 11f;
    private static final float HEADING_SIZE = 12f;

    private final JLabel titleLabel;
    private final JLabel metaLabel;
    private boolean focusedSection;

    private final PropertyChangeListener focusListener = e -> setFocusedSection(
            e.getNewValue() instanceof Component owner && SwingUtilities.isDescendingFrom(owner, this));

    /**
     * @param title   the section title, shown upper-case
     * @param font    the monospace base font; the meta text uses a smaller derivation
     * @param content the section body
     * @param tools   optional controls placed at the right end of the rule
     */
    public Section(String title, Font font, JComponent content, JComponent... tools) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        setBorder(new EmptyBorder(10, 0, 10, 0));

        Font titleFont = Fontalizer.getUiSemiboldFont().deriveFont(HEADING_SIZE)
                .deriveFont(Map.of(TextAttribute.TRACKING, 0.12f));
        titleLabel = new JLabel(title.toUpperCase());
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(DarculaColors.G7);
        titleLabel.setBorder(new EmptyBorder(0, CONTENT_INSET, 0, 10));

        metaLabel = new JLabel();
        metaLabel.setFont(font.deriveFont(TITLE_SIZE));
        metaLabel.setForeground(DarculaColors.G6);
        metaLabel.setBorder(new EmptyBorder(0, 10, 0, 6));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(metaLabel);
        for (JComponent tool : tools) {
            right.add(tool);
        }

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titleLabel, BorderLayout.WEST);
        header.add(new Rule(), BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(0, CONTENT_INSET, 0, 0));
        body.add(content, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    /** Dim text at the right end of the rule, e.g. {@code "8 fields · 1 set"}; empty hides it. */
    public void setMeta(String text) {
        metaLabel.setText(text == null ? "" : text);
    }

    public boolean isFocusedSection() {
        return focusedSection;
    }

    void setFocusedSection(boolean focused) {
        if (focusedSection != focused) {
            focusedSection = focused;
            titleLabel.setForeground(focused ? DarculaColors.ORANGE : DarculaColors.G7);
            repaint();
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner",
                focusListener);
    }

    @Override
    public void removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner",
                focusListener);
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (focusedSection) {
            Insets in = getInsets();
            g.setColor(DarculaColors.ORANGE);
            g.fillRect(0, in.top, BAR_WIDTH, getHeight() - in.top - in.bottom);
        }
    }

    /** A one-pixel horizontal hairline, vertically centred in its bounds. */
    private static final class Rule extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, 1);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(DarculaColors.G3);
            int y = getHeight() / 2;
            g.fillRect(0, y, getWidth(), 1);
        }
    }
}
