package se.grynna.pager.ui;

import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The keyboard help overlay: lists every action that is actually bound (has a
 * handler in {@link InputHandler}), grouped by {@link UiAction.Group}, as a table
 * Action | Desktop | Pager | Emacs rendered from the {@link KeyMap}. The column of the map's
 * preferred flavour ("Key hints" preference) comes first and is highlighted; an action with
 * no key of a flavour shows the key {@link KeyMap#label(UiAction, KeyFlavour)} falls back to, dimmed.
 * <p>
 * It is styled like the preferences panel (raised-bevel box on the app background)
 * and exposes {@link #actions()} so tests can assert it lists exactly the bound
 * actions without duplicating that list.
 */
public class HelpOverlay extends JPanel {

    private final KeyMap keyMap;
    private final List<UiAction> actions;
    private final List<KeyFlavour> columns;

    public HelpOverlay(KeyMap keyMap, Set<UiAction> boundActions) {
        this.keyMap = keyMap;
        this.actions = Arrays.stream(UiAction.values()).filter(boundActions::contains).toList();
        this.columns = columnOrder(keyMap.flavour());

        setBackground(DarculaColors.BACKGROUND);
        setLayout(new GridBagLayout());
        add(new HelpBox(), new GridBagConstraints());
    }

    /** The bound actions in declaration order — exactly what the overlay lists. */
    List<UiAction> actions() {
        return actions;
    }

    /** The flavour columns left to right: the preferred one first, then the others in declaration order. */
    List<KeyFlavour> columns() {
        return columns;
    }

    private static List<KeyFlavour> columnOrder(KeyFlavour preferred) {
        List<KeyFlavour> order = new ArrayList<>();
        order.add(preferred);
        for (KeyFlavour flavour : KeyFlavour.values()) {
            if (flavour != preferred) {
                order.add(flavour);
            }
        }
        return List.copyOf(order);
    }

    /** True when {@code action} has a key of exactly {@code flavour}; false means the cell shows the fallback. */
    boolean hasKey(UiAction action, KeyFlavour flavour) {
        return keyMap.hasFlavour(action, flavour);
    }

    /** One cell of the table, e.g. {@code "F3"} or {@code "n"}: the flavour's keys, else the fallback key. */
    String cell(UiAction action, KeyFlavour flavour) {
        return hasKey(action, flavour) ? keyMap.labels(action, flavour) : keyMap.label(action, flavour);
    }

    private final class HelpBox extends JPanel {
        HelpBox() {
            setPreferredSize(new Dimension(760, 520));
            setLayout(new BorderLayout(0, 0));
            setBackground(DarculaColors.BACKGROUND);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createRaisedBevelBorder(),
                    BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.WHITE, 1),
                            new EmptyBorder(12, 12, 12, 12))));

            add(title(), BorderLayout.NORTH);
            add(new JScrollPane(rows()), BorderLayout.CENTER);
        }

        private JComponent title() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(DarculaColors.BACKGROUND);

            JLabel heading = new JLabel("=== KEYBOARD HELP ===");
            heading.setFont(Fontalizer.getUiSemiboldFont());
            heading.setForeground(DarculaColors.FOREGROUND);
            heading.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel hint = new JLabel("Every key works; hints show " + keyMap.flavour().displayName()
                    + " keys first (Preferences: key hints). Dimmed: that column has no key of its own.");
            hint.setFont(Fontalizer.getFont().deriveFont(11f));
            hint.setForeground(DarculaColors.COMMENT);
            hint.setAlignmentX(Component.CENTER_ALIGNMENT);

            panel.add(heading);
            panel.add(hint);
            return panel;
        }

        private JComponent rows() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(DarculaColors.BACKGROUND);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;

            addCell(panel, gbc, 0, headerLabel("Action", false), 1.0);
            for (int i = 0; i < columns.size(); i++) {
                addCell(panel, gbc, i + 1, headerLabel(columns.get(i).displayName(), i == 0), 0.0);
            }

            for (UiAction.Group group : UiAction.Group.values()) {
                List<UiAction> grouped = actions.stream().filter(a -> a.group() == group).toList();
                if (grouped.isEmpty()) {
                    continue;
                }
                gbc.gridy++;
                gbc.gridwidth = columns.size() + 1;
                gbc.gridx = 0;
                gbc.weightx = 1.0;
                panel.add(groupHeader(group), gbc);
                gbc.gridwidth = 1;
                for (UiAction action : grouped) {
                    gbc.gridy++;
                    addCell(panel, gbc, 0, actionLabel(action), 1.0);
                    for (int i = 0; i < columns.size(); i++) {
                        addCell(panel, gbc, i + 1, keyLabel(action, columns.get(i), i == 0), 0.0);
                    }
                }
            }
            return panel;
        }

        private void addCell(JPanel panel, GridBagConstraints gbc, int column, JComponent cell, double weightx) {
            gbc.gridx = column;
            gbc.weightx = weightx;
            panel.add(cell, gbc);
        }

        private JLabel headerLabel(String text, boolean preferred) {
            JLabel label = new JLabel(text);
            label.setFont(Fontalizer.getUiSemiboldFont().deriveFont(12f));
            label.setForeground(preferred ? DarculaColors.CYAN : DarculaColors.FOREGROUND);
            label.setBorder(new EmptyBorder(0, 0, 4, 16));
            return label;
        }

        private JComponent groupHeader(UiAction.Group group) {
            JLabel label = new JLabel(group.name().replace('_', ' '));
            label.setFont(Fontalizer.getUiSemiboldFont().deriveFont(12f));
            label.setForeground(DarculaColors.CYAN);
            label.setBorder(new EmptyBorder(8, 0, 2, 0));
            return label;
        }

        private JLabel actionLabel(UiAction action) {
            JLabel description = new JLabel(action.description());
            description.setFont(Fontalizer.getFont());
            description.setForeground(DarculaColors.FOREGROUND);
            description.setBorder(new EmptyBorder(1, 0, 1, 16));
            return description;
        }

        private JLabel keyLabel(UiAction action, KeyFlavour flavour, boolean preferred) {
            JLabel keys = new JLabel(cell(action, flavour));
            keys.setFont(preferred ? Fontalizer.getBoldFont() : Fontalizer.getFont());
            boolean own = hasKey(action, flavour);
            keys.setForeground(
                    own ? (preferred ? DarculaColors.CYAN : DarculaColors.FOREGROUND) : DarculaColors.COMMENT);
            keys.setBorder(new EmptyBorder(1, 0, 1, 16));
            return keys;
        }
    }
}
