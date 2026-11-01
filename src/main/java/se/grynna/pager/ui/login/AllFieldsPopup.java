package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.Scope;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.login.FieldGrouping.Entry;
import se.grynna.pager.ui.login.FieldGrouping.Heading;
import se.grynna.pager.ui.login.FieldGrouping.Row;
import se.grynna.pager.ui.login.FieldSuggestions.SuggestedField;
import se.grynna.pager.ui.login.FieldSuggestions.ValueShare;
import se.grynna.pager.ui.login.SuggestedFieldsPanel.Cell;
import se.grynna.pager.ui.login.SuggestedFieldsPanel.CellKind;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import static se.grynna.pager.ui.login.SuggestedFieldsPanel.CELL_GAP;
import static se.grynna.pager.ui.login.SuggestedFieldsPanel.CELL_HEIGHT;
import static se.grynna.pager.ui.login.SuggestedFieldsPanel.CELL_PAD_X;
import static se.grynna.pager.ui.login.SuggestedFieldsPanel.LABEL_MAX_WIDTH;
import static se.grynna.pager.ui.login.SuggestedFieldsPanel.LABEL_MIN_WIDTH;
import static se.grynna.pager.ui.login.SuggestedFieldsPanel.LABEL_RIGHT_GAP;
import static se.grynna.pager.ui.login.SuggestedFieldsPanel.ROW_HEIGHT;
import static se.grynna.pager.ui.login.SuggestedFieldsPanel.SHARE_GAP;

/**
 * Popup over the scope selector listing all index fields under their {@link FieldGrouping} headings, with a filter
 * field at the top that matches both field names and values.
 * <p>
 * Each field row shows its short label, up to {@link FieldSuggestions#MAX_POPUP_VALUES} values with their sample
 * share percentages, and {@code +N} when more values exist. Uses the same keyboard and mouse grid model as
 * {@link SuggestedFieldsPanel}; changes apply to the draft scope immediately and Esc closes the popup.
 */
public final class AllFieldsPopup extends JPanel {

    private static final String FILTER_PLACEHOLDER = "filter fields or values";
    private static final String HINT_TEXT = "Enter / click: narrow \u00B7 - / Shift+Enter / Ctrl-click: exclude \u00B7 Esc: close";
    private static final int HEADING_HEIGHT = 26;
    private static final int HEADING_INDENT = 16;
    private static final int LINE_VGAP = 4;
    private static final int DEFAULT_WIDTH = 920;
    private static final int MAX_HEIGHT = 520;

    private sealed interface LayoutItem permits HeadingItem, FieldRowItem {
        int y();

        int height();
    }

    private record HeadingItem(int depth, String text, int y, int height) implements LayoutItem {}

    private record FieldRowItem(int rowIndex, Row row, SuggestedField field, List<Cell> cells, int y, int height,
            boolean stripe) implements LayoutItem {}

    private final UnaryOperator<Scope> scopeEditor;
    private final Runnable onClose;
    private final JTextField filterField = new JTextField();
    private final GridCanvas canvas = new GridCanvas();
    private final JScrollPane scrollPane;
    private final Font font = Fontalizer.getFont().deriveFont(12f);
    private final Font boldFont = Fontalizer.getBoldFont().deriveFont(12f);

    private List<Entry> groupedEntries = List.of();
    private Scope draft = Scope.ofValues(null, Map.of());
    private int selectedRow = 0;
    private int selectedCol = 1;
    private int hoverRow = -1;
    private Cell hoverCell;

    public AllFieldsPopup(UnaryOperator<Scope> scopeEditor, Runnable onClose) {
        super(new BorderLayout());
        this.scopeEditor = scopeEditor;
        this.onClose = onClose;
        setBackground(DarculaColors.BACKGROUND_OUTSIDE);

        filterField.setFont(font);
        filterField.putClientProperty("JTextField.placeholderText", FILTER_PLACEHOLDER);
        filterField.putClientProperty("JTextField.showClearButton", true);
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onFilterChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onFilterChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onFilterChanged();
            }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(6, 8, 6, 8));
        top.add(filterField, BorderLayout.CENTER);

        scrollPane = new JScrollPane(canvas);
        scrollPane.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, DarculaColors.G3));
        scrollPane.getViewport().setBackground(DarculaColors.BACKGROUND);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);

        JLabel hint = new JLabel(HINT_TEXT);
        hint.setFont(font.deriveFont(11f));
        hint.setForeground(DarculaColors.G6);
        hint.setBorder(new EmptyBorder(4, 10, 4, 10));

        add(top, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);

        bindKeys(filterField);
        bindKeys(canvas);
    }

    /** Updates the grouped entries and draft scope shown in the popup. */
    public void updateState(List<Entry> entries, Scope newDraft) {
        this.groupedEntries = entries == null ? List.of() : List.copyOf(entries);
        this.draft = newDraft == null ? Scope.ofValues(null, Map.of()) : newDraft;
        clampSelection();
        canvas.revalidate();
        repaint();
    }

    /** Sets the filter text and resets selection to the first matching row's first value cell. */
    public void setFilterText(String text) {
        filterField.setText(text == null ? "" : text);
        selectedRow = 0;
        selectedCol = 1;
        clampSelection();
    }

    public String filterText() {
        return filterField.getText();
    }

    public void focusFilterField() {
        filterField.requestFocusInWindow();
        filterField.selectAll();
    }

    // --- Package-private test hooks ---

    List<String> visibleFieldNames() {
        return visibleFieldItems(layoutItems()).stream()
                .map(item -> item.row().field().primary())
                .toList();
    }

    List<String> visibleHeadings() {
        return layoutItems().stream()
                .filter(HeadingItem.class::isInstance)
                .map(HeadingItem.class::cast)
                .map(h -> h.depth() + ":" + h.text())
                .toList();
    }

    void selectCell(int row, int col) {
        this.selectedRow = row;
        this.selectedCol = col;
        clampSelection();
        repaint();
    }

    void pressKey(int keyCode, int modifiers) {
        KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, modifiers);
        Object key = canvas.getInputMap(WHEN_FOCUSED).get(stroke);
        if (key != null && canvas.getActionMap().get(key) != null) {
            canvas.getActionMap().get(key)
                    .actionPerformed(new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, key.toString()));
            return;
        }
        key = filterField.getInputMap(WHEN_FOCUSED).get(stroke);
        if (key != null && filterField.getActionMap().get(key) != null) {
            filterField.getActionMap().get(key)
                    .actionPerformed(new ActionEvent(filterField, ActionEvent.ACTION_PERFORMED, key.toString()));
        }
    }

    void typeChar(char c) {
        KeyStroke stroke = KeyStroke.getKeyStroke(c);
        Object key = canvas.getInputMap(WHEN_FOCUSED).get(stroke);
        if (key != null && canvas.getActionMap().get(key) != null) {
            canvas.getActionMap().get(key)
                    .actionPerformed(new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, key.toString()));
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int contentH = Math.clamp(canvas.getPreferredSize().height, 140, MAX_HEIGHT);
        int topH = filterField.getPreferredSize().height + 12;
        return new Dimension(DEFAULT_WIDTH, topH + contentH + 28);
    }

    // --- Keys ---

    private void bindKeys(JComponent target) {
        bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "popup-up", () -> moveRow(-1));
        bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "popup-down", () -> moveRow(1));
        bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "popup-left", () -> moveCol(-1));
        bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "popup-right", () -> moveCol(1));
        bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "popup-enter", this::activateSelectedPrimary);
        bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "popup-shift-enter",
                this::activateSelectedExclude);
        bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "popup-esc", () -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        if (target == canvas) {
            bind(target, KeyStroke.getKeyStroke('-'), "popup-minus", this::activateSelectedExclude);
            bind(target, KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), "popup-minus-vk",
                    this::activateSelectedExclude);
        }
    }

    private void bind(JComponent target, KeyStroke stroke, String name, Runnable action) {
        target.getInputMap(WHEN_FOCUSED).put(stroke, name);
        target.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void onFilterChanged() {
        selectedRow = 0;
        selectedCol = 1;
        clampSelection();
        canvas.revalidate();
        canvas.repaint();
    }

    private void moveRow(int delta) {
        List<FieldRowItem> fieldRows = visibleFieldItems(layoutItems());
        if (fieldRows.isEmpty()) {
            return;
        }
        selectedRow = Math.clamp(selectedRow + delta, 0, fieldRows.size() - 1);
        clampSelection();
        FieldRowItem item = fieldRows.get(selectedRow);
        canvas.scrollRectToVisible(new Rectangle(0, item.y(), 100, item.height()));
        canvas.repaint();
    }

    private void moveCol(int delta) {
        List<FieldRowItem> fieldRows = visibleFieldItems(layoutItems());
        if (fieldRows.isEmpty()) {
            return;
        }
        selectedRow = Math.clamp(selectedRow, 0, fieldRows.size() - 1);
        List<Cell> cells = fieldRows.get(selectedRow).cells();
        selectedCol = Math.clamp(selectedCol + delta, 0, cells.size() - 1);
        canvas.repaint();
    }

    private void clampSelection() {
        List<FieldRowItem> fieldRows = visibleFieldItems(layoutItems());
        if (fieldRows.isEmpty()) {
            selectedRow = 0;
            selectedCol = 0;
            return;
        }
        selectedRow = Math.clamp(selectedRow, 0, fieldRows.size() - 1);
        List<Cell> cells = fieldRows.get(selectedRow).cells();
        selectedCol = Math.clamp(selectedCol, 0, Math.max(0, cells.size() - 1));
    }

    private Cell currentCell() {
        List<FieldRowItem> fieldRows = visibleFieldItems(layoutItems());
        if (selectedRow < 0 || selectedRow >= fieldRows.size()) {
            return null;
        }
        List<Cell> cells = fieldRows.get(selectedRow).cells();
        if (selectedCol < 0 || selectedCol >= cells.size()) {
            return null;
        }
        return cells.get(selectedCol);
    }

    private void activateSelectedPrimary() {
        Cell cell = currentCell();
        if (cell != null) {
            activateCell(cell);
        }
    }

    private void activateSelectedExclude() {
        Cell cell = currentCell();
        if (cell != null && cell.kind() == CellKind.VALUE) {
            toggleExclude(cell.field(), cell.valueShare().value());
        }
    }

    private void activateCell(Cell cell) {
        switch (cell.kind()) {
            case VALUE -> toggleNarrow(cell.field(), cell.valueShare().value());
            case LABEL, MORE -> setFilterText(cell.field().primary());
        }
    }

    private void toggleNarrow(SuggestedField sf, String value) {
        if (FieldSuggestions.isValueNarrowed(sf.field(), value, draft)) {
            Scope next = draft;
            for (String f : sf.field().fields()) {
                next = next.withoutFieldValue(f, value);
            }
            scopeEditor.apply(next);
        } else {
            scopeEditor.apply(FieldSuggestions.withNarrowFieldValue(draft, sf.field(), value));
        }
    }

    private void toggleExclude(SuggestedField sf, String value) {
        if (FieldSuggestions.isValueExcluded(sf.field(), value, draft)) {
            scopeEditor.apply(FieldSuggestions.withoutExcludeFieldValue(draft, sf.field(), value));
        } else {
            scopeEditor.apply(FieldSuggestions.withExcludeFieldValue(draft, sf.field(), value));
        }
    }

    // --- Filtering & Layout ---

    private boolean matchesFilter(Row row, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        if (row.label().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String name : row.field().fields()) {
            if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        for (String val : row.field().values().keySet()) {
            if (val != null && val.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<ValueShare> popupValuesFor(LogicalField field, String needle) {
        long total = FieldSuggestions.totalOccurrences(field);
        List<Map.Entry<String, Long>> all = new ArrayList<>();
        field.values().forEach((val, count) -> {
            if (val != null && !val.isBlank()) {
                all.add(Map.entry(val, count));
            }
        });
        boolean nameMatches = needle.isEmpty()
                || field.fields().stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains(needle));
        all.sort(Comparator
                .comparing((Map.Entry<String, Long> e) -> !nameMatches
                        && !e.getKey().toLowerCase(Locale.ROOT).contains(needle))
                .thenComparing(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .thenComparing(Map.Entry.comparingByKey()));
        int n = Math.min(FieldSuggestions.MAX_POPUP_VALUES, all.size());
        List<ValueShare> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            var e = all.get(i);
            out.add(new ValueShare(e.getKey(), e.getValue(), FieldSuggestions.sharePercent(e.getValue(), total)));
        }
        return out;
    }

    private List<LayoutItem> layoutItems() {
        if (groupedEntries.isEmpty()) {
            return List.of();
        }
        String rawFilter = filterField.getText();
        String needle = rawFilter == null ? "" : rawFilter.strip().toLowerCase(Locale.ROOT);

        boolean[] visible = new boolean[groupedEntries.size()];
        for (int i = 0; i < groupedEntries.size(); i++) {
            if (groupedEntries.get(i) instanceof Row row) {
                visible[i] = matchesFilter(row, needle);
            }
        }
        for (int i = 0; i < groupedEntries.size(); i++) {
            if (groupedEntries.get(i) instanceof Heading heading) {
                boolean anyChild = false;
                for (int j = i + 1; j < groupedEntries.size(); j++) {
                    if (groupedEntries.get(j) instanceof Heading next && next.depth() <= heading.depth()) {
                        break;
                    }
                    if (groupedEntries.get(j) instanceof Row && visible[j]) {
                        anyChild = true;
                        break;
                    }
                }
                visible[i] = anyChild;
            }
        }

        FontMetrics fm = getFontMetrics(font);
        int longestLabel = 0;
        for (int i = 0; i < groupedEntries.size(); i++) {
            if (visible[i] && groupedEntries.get(i) instanceof Row row) {
                longestLabel = Math.max(longestLabel, fm.stringWidth(row.label()) + 2 * CELL_PAD_X);
            }
        }
        int labelColWidth = Math.clamp(longestLabel, LABEL_MIN_WIDTH, LABEL_MAX_WIDTH);
        int availWidth = canvas.getWidth() > 0 ? canvas.getWidth() : DEFAULT_WIDTH - 24;
        int maxCellX = Math.max(labelColWidth + 240, availWidth - 10);

        List<LayoutItem> items = new ArrayList<>();
        int y = 4;
        int fieldRowIdx = 0;
        boolean stripe = false;

        for (int i = 0; i < groupedEntries.size(); i++) {
            if (!visible[i]) {
                continue;
            }
            Entry entry = groupedEntries.get(i);
            switch (entry) {
                case Heading h -> {
                    stripe = false;
                    items.add(new HeadingItem(h.depth(), h.text(), y, HEADING_HEIGHT));
                    y += HEADING_HEIGHT;
                }
                case Row row -> {
                    List<ValueShare> top = popupValuesFor(row.field(), needle);
                    int totalVals = (int) row.field().values().keySet().stream().filter(k -> k != null && !k.isBlank())
                            .count();
                    int more = Math.max(0, totalVals - top.size());
                    SuggestedField sf = new SuggestedField(row.label(), row.field(), top, more);

                    List<Cell> cells = new ArrayList<>();
                    int firstLineCenterY = y + (ROW_HEIGHT - CELL_HEIGHT) / 2;
                    String elidedLabel = SuggestedFieldsPanel.elideLabelLeft(fm, row.label(),
                            labelColWidth - 2 * CELL_PAD_X);
                    int labelW = fm.stringWidth(elidedLabel) + 2 * CELL_PAD_X;
                    int labelX = Math.max(8, labelColWidth - labelW);
                    cells.add(new Cell(fieldRowIdx, 0, CellKind.LABEL, sf, null, 0, elidedLabel, null, false, false,
                            new Rectangle(labelX, firstLineCenterY, labelW, CELL_HEIGHT)));

                    int valStartX = labelColWidth + LABEL_RIGHT_GAP;
                    int x = valStartX;
                    int lineY = firstLineCenterY;
                    int col = 1;

                    for (ValueShare vs : top) {
                        String valText = SuggestedFieldsPanel.elideValue(vs.value());
                        String shareText = vs.sharePercent() + "%";
                        int cellW = CELL_PAD_X + fm.stringWidth(valText) + SHARE_GAP + fm.stringWidth(shareText)
                                + CELL_PAD_X;
                        if (x > valStartX && x + cellW > maxCellX) {
                            x = valStartX;
                            lineY += CELL_HEIGHT + LINE_VGAP;
                        }
                        boolean narrowed = FieldSuggestions.isValueNarrowed(row.field(), vs.value(), draft);
                        boolean excluded = FieldSuggestions.isValueExcluded(row.field(), vs.value(), draft);
                        cells.add(new Cell(fieldRowIdx, col++, CellKind.VALUE, sf, vs, 0, valText, shareText,
                                narrowed, excluded, new Rectangle(x, lineY, cellW, CELL_HEIGHT)));
                        x += cellW + CELL_GAP;
                    }

                    if (more > 0) {
                        String moreText = "+" + more;
                        int moreW = fm.stringWidth(moreText) + 2 * CELL_PAD_X;
                        if (x > valStartX && x + moreW > maxCellX) {
                            x = valStartX;
                            lineY += CELL_HEIGHT + LINE_VGAP;
                        }
                        cells.add(new Cell(fieldRowIdx, col, CellKind.MORE, sf, null, more, moreText, null, false,
                                false, new Rectangle(x, lineY, moreW, CELL_HEIGHT)));
                    }

                    int rowHeight = Math.max(ROW_HEIGHT, (lineY - y) + CELL_HEIGHT + 4);
                    items.add(new FieldRowItem(fieldRowIdx, row, sf, cells, y, rowHeight, stripe));
                    stripe = !stripe;
                    y += rowHeight;
                    fieldRowIdx++;
                }
            }
        }
        return items;
    }

    private static List<FieldRowItem> visibleFieldItems(List<LayoutItem> items) {
        List<FieldRowItem> rows = new ArrayList<>();
        for (LayoutItem item : items) {
            if (item instanceof FieldRowItem fri) {
                rows.add(fri);
            }
        }
        return rows;
    }

    private final class GridCanvas extends JComponent {

        GridCanvas() {
            setFocusable(true);
            setOpaque(true);
            setBackground(DarculaColors.BACKGROUND);
            ToolTipManager.sharedInstance().registerComponent(this);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    List<FieldRowItem> rows = visibleFieldItems(layoutItems());
                    Cell cell = cellAt(rows, e.getPoint());
                    int r = rowAt(rows, e.getPoint().y);
                    if (cell != hoverCell || r != hoverRow) {
                        hoverCell = cell;
                        hoverRow = r;
                        setCursor(Cursor.getPredefinedCursor(
                                cell != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverCell = null;
                    hoverRow = -1;
                    setCursor(Cursor.getDefaultCursor());
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    List<FieldRowItem> rows = visibleFieldItems(layoutItems());
                    Cell cell = cellAt(rows, e.getPoint());
                    if (cell == null) {
                        return;
                    }
                    selectedRow = cell.row();
                    selectedCol = cell.col();
                    requestFocusInWindow();
                    boolean excludeGesture = SwingUtilities.isRightMouseButton(e)
                            || e.isControlDown()
                            || e.isShiftDown();
                    if (excludeGesture && cell.kind() == CellKind.VALUE) {
                        toggleExclude(cell.field(), cell.valueShare().value());
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        activateCell(cell);
                    }
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private Cell cellAt(List<FieldRowItem> rows, Point p) {
            for (FieldRowItem r : rows) {
                for (Cell cell : r.cells()) {
                    if (cell.bounds().contains(p)) {
                        return cell;
                    }
                }
            }
            return null;
        }

        private int rowAt(List<FieldRowItem> rows, int y) {
            for (FieldRowItem r : rows) {
                if (y >= r.y() && y < r.y() + r.height()) {
                    return r.rowIndex();
                }
            }
            return -1;
        }

        @Override
        public Dimension getPreferredSize() {
            List<LayoutItem> items = layoutItems();
            if (items.isEmpty()) {
                return new Dimension(DEFAULT_WIDTH - 24, 60);
            }
            LayoutItem last = items.getLast();
            return new Dimension(DEFAULT_WIDTH - 24, last.y() + last.height() + 8);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            Cell cell = cellAt(visibleFieldItems(layoutItems()), event.getPoint());
            return cell == null ? null : SuggestedFieldsPanel.cellTooltip(cell);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                g2.setColor(DarculaColors.BACKGROUND);
                g2.fillRect(0, 0, getWidth(), getHeight());

                List<LayoutItem> items = layoutItems();
                FontMetrics fm = g2.getFontMetrics(font);
                FontMetrics bfm = g2.getFontMetrics(boldFont);

                for (LayoutItem item : items) {
                    switch (item) {
                        case HeadingItem h -> {
                            g2.setFont(h.depth() == 0 ? boldFont : font);
                            g2.setColor(h.depth() == 0 ? DarculaColors.G8 : DarculaColors.G7);
                            FontMetrics m = h.depth() == 0 ? bfm : fm;
                            int baseline = h.y() + (h.height() - m.getHeight()) / 2 + m.getAscent();
                            g2.drawString(h.text() + " \u203A", 10 + h.depth() * HEADING_INDENT, baseline);
                        }
                        case FieldRowItem fri -> {
                            boolean rowHover = fri.rowIndex() == hoverRow;
                            if (rowHover || fri.stripe()) {
                                g2.setColor(rowHover ? DarculaColors.LIST_HOVER : DarculaColors.LIST_STRIPE);
                                g2.fillRect(0, fri.y(), getWidth(), fri.height());
                            }
                            if (FieldSuggestions.isInDraft(fri.field().field(), draft)) {
                                g2.setColor(DarculaColors.ORANGE);
                                g2.fillRect(0, fri.y() + 2, 2, fri.height() - 4);
                            }
                            for (Cell cell : fri.cells()) {
                                boolean selected = fri.rowIndex() == selectedRow && cell.col() == selectedCol;
                                boolean hovered = cell.equals(hoverCell);
                                SuggestedFieldsPanel.paintCell(g2, fm, cell, selected, hovered);
                            }
                        }
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
