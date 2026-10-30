package se.grynna.pager.ui.login;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.SplQuery;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.autocomplete.AutoCompletion2;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One field filter row: the field's short label under its group
 * headings, the number of sampled values, an editable value combo sized to its values and a clear button.
 * The field may stand for several real Splunk fields with the same token path ({@link LogicalField});
 * the filter then matches when any of them has the value.
 */
@Slf4j
public class FilterRow extends ListRow {
    private final LogicalField field;
    private final JComboBox<String> valueComboBox;
    private final JButton clearButton;
    private final Runnable onChangeCallback;
    private final boolean highlighted;
    private static final Dimension CLEAR_BUTTON_SIZE = new Dimension(24, 24);
    private static final int VALUE_COMBOBOX_MIN_WIDTH = 200;
    private static final int VALUE_COMBOBOX_MAX_WIDTH = 480;
    private static final int VALUE_COMBOBOX_HEIGHT = 24;
    private static final String CLEAR_BUTTON_TEXT = "×";
    private static final String CLEAR_FILTER_TOOLTIP = "Clear filter";

    /**
     * @param field             the field and its sampled values
     * @param label             the text shown for it, e.g. {@code name} under {@code kubernetes › pod}
     * @param onChangeCallback  called when the value changes
     * @param highlightPatterns compiled glob patterns of fields to highlight (null or empty: none)
     */
    public FilterRow(LogicalField field, String label, Runnable onChangeCallback, List<Pattern> highlightPatterns) {
        this(field, label, onChangeCallback, highlightPatterns, shouldHighlight(field, highlightPatterns));
    }

    private FilterRow(LogicalField field, String label, Runnable onChangeCallback, List<Pattern> highlightPatterns,
            boolean highlighted) {
        super(nameLabel(label, highlighted), countLabel(field.values().size()));
        this.field = field;
        this.onChangeCallback = onChangeCallback;
        this.highlighted = highlighted;
        setToolTipText(null);
        String tooltip = tooltip(field, highlighted);
        for (Component c : ((JComponent) getComponent(0)).getComponents()) {
            if (c instanceof JComponent jc) {
                jc.setToolTipText(tooltip);
            }
        }

        // "" first: a combo starts empty, values are only set from saved filters
        String[] values = new String[field.values().size() + 1];
        values[0] = "";
        int index = 1;
        for (String val : field.values().keySet()) {
            values[index++] = val;
        }

        valueComboBox = new JComboBox<>(values);
        valueComboBox.setEditable(true);
        valueComboBox.setFont(Fontalizer.getFont());
        // As wide as its widest value, within bounds, so it sits close to its label on a wide panel
        int comboWidth = Math.clamp(valueComboBox.getPreferredSize().width, VALUE_COMBOBOX_MIN_WIDTH,
                VALUE_COMBOBOX_MAX_WIDTH);
        valueComboBox.setPreferredSize(new Dimension(comboWidth, VALUE_COMBOBOX_HEIGHT));
        valueComboBox.setSelectedItem("");
        valueComboBox.setBackground(DarculaColors.BACKGROUND_OUTSIDE);

        AutoCompletion2.enable(valueComboBox);

        valueComboBox.addActionListener(e -> {
            onChangeCallback.run();
            repaint();
        });

        clearButton = new JButton(CLEAR_BUTTON_TEXT);
        clearButton.setFont(Fontalizer.getFont());
        clearButton.setForeground(DarculaColors.G7);
        clearButton.putClientProperty("JButton.buttonType", "toolBarButton");
        clearButton.setPreferredSize(CLEAR_BUTTON_SIZE);
        clearButton.setFocusPainted(false);
        clearButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearButton.setToolTipText(CLEAR_FILTER_TOOLTIP);
        clearButton.addActionListener(e -> clearValue());

        add(valueGroup(valueComboBox, clearButton), BorderLayout.CENTER);
        trackHover();
    }

    private static JLabel nameLabel(String label, boolean highlighted) {
        JLabel name = new JLabel(label);
        name.setFont(highlighted ? Fontalizer.getBoldFont() : Fontalizer.getFont());
        name.setForeground(highlighted ? DarculaColors.BORDER_HIGHLIGHT : DarculaColors.FOREGROUND);
        return name;
    }

    private static JLabel countLabel(int valueCount) {
        JLabel count = new JLabel(String.valueOf(valueCount));
        count.setFont(Fontalizer.getFont());
        count.setForeground(DarculaColors.G7);
        return count;
    }

    /** The real field names, one per line, with the value count; ★ marks a highlighted field. */
    private static String tooltip(LogicalField field, boolean highlighted) {
        StringBuilder text = new StringBuilder("<html>");
        for (String name : field.fields()) {
            text.append(name.replace("&", "&amp;").replace("<", "&lt;")).append("<br>");
        }
        text.append(field.values().size()).append(" values").append(highlighted ? " ★" : "");
        return text.append("</html>").toString();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!getFilterValue().isEmpty()) {
            Insets insets = getInsets();
            g.setColor(DarculaColors.ORANGE);
            g.fillRect(0, insets.top, 2, getHeight() - insets.top - insets.bottom);
        }
    }

    /**
     * Returns true if this field is highlighted (matches highlight patterns).
     */
    public boolean isHighlighted() {
        return highlighted;
    }

    /** Whether any of the field's real names matches any of the highlight patterns. */
    private static boolean shouldHighlight(LogicalField field, List<Pattern> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        return field.fields().stream().anyMatch(name -> patterns.stream().anyMatch(p -> p.matcher(name).matches()));
    }

    /** Whether the row is found by {@code query}: a case-insensitive substring of a real field name. */
    boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return field.fields().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).contains(needle));
    }

    /**
     * Convert glob patterns (comma-separated) to compiled regex patterns.
     * Supports * (any chars) and ? (single char) wildcards.
     * Example: "*_pod_name,*_container_name"
     */
    public static List<Pattern> compileGlobPatterns(String globPatterns) {
        if (globPatterns == null || globPatterns.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(globPatterns.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(FilterRow::globToRegex).map(Pattern::compile).toList();
    }

    /**
     * Convert a glob pattern to a regex pattern.
     * * matches any sequence of characters
     * ? matches a single character
     */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '\\' -> regex.append("\\\\");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    /** {@code field="value"}, or {@code (a="value" OR b="value")} for several real fields; empty without a value. */
    public String getFilterString() {
        String value = getFilterValue();
        return value.isEmpty() ? "" : SplQuery.fieldEqualsAny(field.fields(), value);
    }

    public void clearValue() {
        valueComboBox.setSelectedItem("");
        onChangeCallback.run();
    }

    /** The primary real field name: the key the filter is saved and applied under. */
    public String getFieldName() {
        return field.primary();
    }

    /** All real field names, primary first. */
    public List<String> getFields() {
        return field.fields();
    }

    /** The real field names other than the primary one. */
    public List<String> getAliases() {
        return field.fields().subList(1, field.fields().size());
    }

    /**
     * Get the current filter value.
     */
    public String getFilterValue() {
        Object selectedItem = valueComboBox.getSelectedItem();
        if (selectedItem == null) return "";
        return selectedItem.toString().trim();
    }

    /**
     * Set the filter value for this row.
     */
    public void setFilterValue(String value) {
        log.debug("Setting filter value for field '{}' to '{}'", field.primary(), value);
        if (value == null) value = "";
        valueComboBox.setSelectedItem(value);
    }
}
