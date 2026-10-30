package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;

/**
 * A saved narrowing or excluded text term, listed with the field filter rows of its index.
 * The × removes the term, like a field filter row's clear button.
 */
public class ScopeTermRow extends ListRow {

    private static final Dimension REMOVE_BUTTON_SIZE = new Dimension(24, 24);
    private static final String REMOVE_TOOLTIP = "Remove from the scope";

    private final JButton removeButton;

    /**
     * @param key      the column text, for example {@code "+ narrow"} or {@code "− exclude"}
     * @param term     the term, shown as the value
     * @param onRemove called on the EDT when the × is pressed
     */
    public ScopeTermRow(String key, String term, Runnable onRemove) {
        super(keyLabel(key), null);

        JLabel value = new JLabel(FieldTerm.display(term));
        value.setFont(Fontalizer.getFont());
        value.setForeground(DarculaColors.FOREGROUND);
        value.setToolTipText(FieldTerm.display(term));

        removeButton = new JButton("×");
        removeButton.setFont(Fontalizer.getFont());
        removeButton.setForeground(DarculaColors.G7);
        removeButton.putClientProperty("JButton.buttonType", "toolBarButton");
        removeButton.setPreferredSize(REMOVE_BUTTON_SIZE);
        removeButton.setFocusPainted(false);
        removeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeButton.setToolTipText(REMOVE_TOOLTIP);
        removeButton.addActionListener(e -> onRemove.run());

        add(valueGroup(value, removeButton), BorderLayout.CENTER);
        trackHover();
    }

    private static JLabel keyLabel(String key) {
        JLabel label = new JLabel(key);
        label.setFont(Fontalizer.getFont());
        label.setForeground(key.startsWith("+") ? DarculaColors.CYAN : DarculaColors.PINK);
        return label;
    }

    JButton removeButton() {
        return removeButton;
    }
}
