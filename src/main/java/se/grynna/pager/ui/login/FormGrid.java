package se.grynna.pager.ui.login;

import com.formdev.flatlaf.FlatClientProperties;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;

/**
 * A two-column form as used by the login box and the preferences: dim lower-case SemiBold labels in a
 * fixed-width column, then the fields.
 */
public final class FormGrid {

    private FormGrid() {}

    /**
     * @param labelWidth the width of the label column
     * @param labels     one label per field
     * @param fields     the fields, in row order
     */
    public static JPanel of(int labelWidth, String[] labels, JComponent... fields) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(3, 0, 3, 0);
        for (int i = 0; i < fields.length; i++) {
            gbc.gridy = i;
            gbc.gridx = 0;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            form.add(label(labels[i], labelWidth), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            form.add(fields[i], gbc);
        }
        return form;
    }

    static JLabel label(String text, int width) {
        JLabel label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
        label.setForeground(DarculaColors.G7);
        label.setPreferredSize(new Dimension(width, label.getPreferredSize().height));
        return label;
    }
}
