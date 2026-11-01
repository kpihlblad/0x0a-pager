package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.Scope;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.login.FieldSuggestions.SuggestedField;
import se.grynna.pager.ui.login.FieldSuggestions.ValueShare;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Displays the suggested fields for the active index as a keyboard- and mouse-navigable grid.
 * <p>
 * Each row has a right-aligned short field label, up to {@link FieldSuggestions#MAX_SUGGESTED_VALUES} top values
 * with their sample share percentages, and a {@code +N} cell when the field has additional values. Narrowed values
 * render as filled chips; excluded values render struck through in the exclude accent colour.
 */
public final class SuggestedFieldsPanel extends JComponent {

    static final int ROW_HEIGHT = 28;
    static final int CELL_HEIGHT = 22;
    static final int CELL_ARC = 8;
    static final int CELL_PAD_X = 8;
    static final int CELL_GAP = 8;
    static final int SHARE_GAP = 5;
    static final int LABEL_MIN_WIDTH = 100;
    static final int LABEL_MAX_WIDTH = 220;
    static final int LABEL_RIGHT_GAP = 14;
    static final int MAX_VALUE_CHARS = 32;
    private static final String ELLIPSIS = "\u2026";

    enum CellKind {
        LABEL, VALUE, MORE
    }

    record Cell(int row, int col, CellKind kind, SuggestedField field, ValueShare valueShare, int moreCount,
            String displayText, String shareText, boolean narrowed, boolean excluded, Rectangle bounds) {}

    private record RowLayout(int row, SuggestedField field, List<Cell> cells, int y) {}

    private final UnaryOperator<Scope> scopeEditor;
    private final Consumer<String> onOpenAllFields;
    private final Runnable onMoveUp;
    private final Font font = Fontalizer.getFont().deriveFont(12f);
    private final Font boldFont = Fontalizer.getBoldFont().deriveFont(12f);

    private List<SuggestedField> rows = List.of();
    private Scope draft = Scope.ofValues(null, Map.of());
    private String emptyMessage = "No fields available for this index.";
    private int selectedRow = 0;
    private int selectedCol = 1;
    private int hoverRow = -1;
    private Cell hoverCell;

    public SuggestedFieldsPanel(UnaryOperator<Scope> scopeEditor, Consumer<String> onOpenAllFields,
            Runnable onMoveUp) {
        this.scopeEditor = scopeEditor;
        this.onOpenAllFields = onOpenAllFields;
        this.onMoveUp = onMoveUp;
        setFont(font);
        setFocusable(true);
        setOpaque(false);
        ToolTipManager.sharedInstance().registerComponent(this);

        setupKeys();
        setupMouse();
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                clampSelection();
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }

    /** Updates the displayed suggested fields and the current draft scope. */
    public void updateState(List<SuggestedField> newRows, Scope newDraft, String emptyStateMessage) {
        this.rows = newRows == null ? List.of() : List.copyOf(newRows);
        this.draft = newDraft == null ? Scope.ofValues(null, Map.of()) : newDraft;
        if (emptyStateMessage != null) {
            this.emptyMessage = emptyStateMessage;
        }
        clampSelection();
        revalidate();
        repaint();
    }

    /** Moves keyboard focus into the first row's first value cell (or label cell if none). */
    public void focusFirstRow() {
        selectedRow = 0;
        selectedCol = 1;
        clampSelection();
        requestFocusInWindow();
        repaint();
    }

    // --- Package-private test hooks ---

    List<SuggestedField> rows() {
        return rows;
    }

    int selectedRow() {
        return selectedRow;
    }

    int selectedCol() {
        return selectedCol;
    }

    void selectCell(int row, int col) {
        this.selectedRow = row;
        this.selectedCol = col;
        clampSelection();
        repaint();
    }

    void pressKey(int keyCode, int modifiers) {
        Object actionKey = getInputMap(WHEN_FOCUSED).get(KeyStroke.getKeyStroke(keyCode, modifiers));
        if (actionKey != null) {
            Action action = getActionMap().get(actionKey);
            if (action != null) {
                action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionKey.toString()));
            }
        }
    }

    void typeChar(char c) {
        Object actionKey = getInputMap(WHEN_FOCUSED).get(KeyStroke.getKeyStroke(c));
        if (actionKey != null) {
            Action action = getActionMap().get(actionKey);
            if (action != null) {
                action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionKey.toString()));
            }
        }
    }

    // --- Keys & Mouse ---

    private void setupKeys() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "grid-up", this::moveUp);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "grid-down", () -> moveRow(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "grid-left", () -> moveCol(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "grid-right", () -> moveCol(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "grid-enter", this::activateSelectedPrimary);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "grid-shift-enter",
                this::activateSelectedExclude);
        bind(KeyStroke.getKeyStroke('-'), "grid-minus-typed", this::activateSelectedExclude);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), "grid-minus-key", this::activateSelectedExclude);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0), "grid-subtract-key", this::activateSelectedExclude);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "grid-all-fields-alt",
                () -> onOpenAllFields.accept(""));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                "grid-all-fields-emacs", () -> onOpenAllFields.accept(""));
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void setupMouse() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                List<RowLayout> layouts = layoutRows();
                Cell cell = cellAt(layouts, e.getPoint());
                int r = rowAt(layouts, e.getPoint().y);
                if (cell != hoverCell || r != hoverRow) {
                    hoverCell = cell;
                    hoverRow = r;
                    setCursor(Cursor.getPredefinedCursor(cell != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverCell != null || hoverRow != -1) {
                    hoverCell = null;
                    hoverRow = -1;
                    setCursor(Cursor.getDefaultCursor());
                    repaint();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                List<RowLayout> layouts = layoutRows();
                Cell cell = cellAt(layouts, e.getPoint());
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

    private void moveUp() {
        if (selectedRow <= 0) {
            if (onMoveUp != null) {
                onMoveUp.run();
            }
            return;
        }
        moveRow(-1);
    }

    private void moveRow(int delta) {
        if (rows.isEmpty()) {
            return;
        }
        selectedRow = Math.clamp(selectedRow + delta, 0, rows.size() - 1);
        clampSelection();
        repaint();
    }

    private void moveCol(int delta) {
        List<RowLayout> layouts = layoutRows();
        if (layouts.isEmpty()) {
            return;
        }
        selectedRow = Math.clamp(selectedRow, 0, layouts.size() - 1);
        List<Cell> cells = layouts.get(selectedRow).cells();
        selectedCol = Math.clamp(selectedCol + delta, 0, cells.size() - 1);
        repaint();
    }

    private void clampSelection() {
        List<RowLayout> layouts = layoutRows();
        if (layouts.isEmpty()) {
            selectedRow = 0;
            selectedCol = 0;
            return;
        }
        selectedRow = Math.clamp(selectedRow, 0, layouts.size() - 1);
        List<Cell> cells = layouts.get(selectedRow).cells();
        selectedCol = Math.clamp(selectedCol, 0, Math.max(0, cells.size() - 1));
    }

    private Cell currentCell() {
        List<RowLayout> layouts = layoutRows();
        if (selectedRow < 0 || selectedRow >= layouts.size()) {
            return null;
        }
        List<Cell> cells = layouts.get(selectedRow).cells();
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
            case LABEL, MORE -> {
                if (onOpenAllFields != null) {
                    onOpenAllFields.accept(cell.field().primary());
                }
            }
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

    // --- Layout & Painting ---

    static String elideValue(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_VALUE_CHARS ? value : value.substring(0, MAX_VALUE_CHARS) + ELLIPSIS;
    }

    private List<RowLayout> layoutRows() {
        if (rows.isEmpty()) {
            return List.of();
        }
        FontMetrics fm = getFontMetrics(font);
        int longestLabel = 0;
        for (SuggestedField sf : rows) {
            longestLabel = Math.max(longestLabel, fm.stringWidth(sf.label()) + 2 * CELL_PAD_X);
        }
        int labelColWidth = Math.clamp(longestLabel, LABEL_MIN_WIDTH, LABEL_MAX_WIDTH);
        int availWidth = getWidth() > 0 ? getWidth() : 1200;

        List<RowLayout> layouts = new ArrayList<>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            SuggestedField sf = rows.get(r);
            int y = r * ROW_HEIGHT;
            int cellY = y + (ROW_HEIGHT - CELL_HEIGHT) / 2;
            List<Cell> cells = new ArrayList<>();

            String elidedLabel = elideLabelLeft(fm, sf.label(), labelColWidth - 2 * CELL_PAD_X);
            int labelTextW = fm.stringWidth(elidedLabel) + 2 * CELL_PAD_X;
            int labelX = Math.max(0, labelColWidth - labelTextW);
            cells.add(new Cell(r, 0, CellKind.LABEL, sf, null, 0, elidedLabel, null, false, false,
                    new Rectangle(labelX, cellY, labelTextW, CELL_HEIGHT)));

            int x = labelColWidth + LABEL_RIGHT_GAP;
            List<ValueShare> top = sf.topValues();
            int shownValues = top.size();
            if (getWidth() > 0) {
                shownValues = countFittingValues(fm, sf, x, availWidth);
            }

            int col = 1;
            for (int i = 0; i < shownValues; i++) {
                ValueShare vs = top.get(i);
                String valText = elideValue(vs.value());
                String shareText = vs.sharePercent() + "%";
                int cellW = CELL_PAD_X + fm.stringWidth(valText) + SHARE_GAP + fm.stringWidth(shareText) + CELL_PAD_X;
                boolean narrowed = FieldSuggestions.isValueNarrowed(sf.field(), vs.value(), draft);
                boolean excluded = FieldSuggestions.isValueExcluded(sf.field(), vs.value(), draft);
                cells.add(new Cell(r, col++, CellKind.VALUE, sf, vs, 0, valText, shareText, narrowed, excluded,
                        new Rectangle(x, cellY, cellW, CELL_HEIGHT)));
                x += cellW + CELL_GAP;
            }

            int hiddenCount = sf.valueCount() - shownValues;
            if (hiddenCount > 0) {
                String moreText = "+" + hiddenCount;
                int moreW = fm.stringWidth(moreText) + 2 * CELL_PAD_X;
                cells.add(new Cell(r, col, CellKind.MORE, sf, null, hiddenCount, moreText, null, false, false,
                        new Rectangle(x, cellY, moreW, CELL_HEIGHT)));
            }
            layouts.add(new RowLayout(r, sf, cells, y));
        }
        return layouts;
    }

    private int countFittingValues(FontMetrics fm, SuggestedField sf, int startX, int availWidth) {
        List<ValueShare> top = sf.topValues();
        int x = startX;
        int shown = 0;
        for (int i = 0; i < top.size(); i++) {
            ValueShare vs = top.get(i);
            String valText = elideValue(vs.value());
            String shareText = vs.sharePercent() + "%";
            int cellW = CELL_PAD_X + fm.stringWidth(valText) + SHARE_GAP + fm.stringWidth(shareText) + CELL_PAD_X;
            int remaining = sf.valueCount() - (i + 1);
            int moreReserve = remaining > 0 ? fm.stringWidth("+" + remaining) + 2 * CELL_PAD_X + CELL_GAP : 0;
            if (i > 0 && x + cellW + moreReserve > availWidth) {
                break;
            }
            x += cellW + CELL_GAP;
            shown++;
        }
        return shown;
    }

    static String elideLabelLeft(FontMetrics fm, String text, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        for (int i = 1; i < text.length(); i++) {
            String candidate = ELLIPSIS + text.substring(i);
            if (fm.stringWidth(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return ELLIPSIS;
    }

    private static Cell cellAt(List<RowLayout> layouts, Point p) {
        for (RowLayout rl : layouts) {
            for (Cell cell : rl.cells()) {
                if (cell.bounds().contains(p)) {
                    return cell;
                }
            }
        }
        return null;
    }

    private static int rowAt(List<RowLayout> layouts, int y) {
        for (RowLayout rl : layouts) {
            if (y >= rl.y() && y < rl.y() + ROW_HEIGHT) {
                return rl.row();
            }
        }
        return -1;
    }

    @Override
    public Dimension getPreferredSize() {
        int count = Math.max(1, rows.size());
        return new Dimension(600, count * ROW_HEIGHT + 4);
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        Cell cell = cellAt(layoutRows(), event.getPoint());
        if (cell == null) {
            return null;
        }
        return cellTooltip(cell);
    }

    static String cellTooltip(Cell cell) {
        SuggestedField sf = cell.field();
        StringBuilder html = new StringBuilder("<html>");
        for (String name : sf.field().fields()) {
            html.append(escapeHtml(name)).append("<br>");
        }
        switch (cell.kind()) {
            case LABEL -> html.append(sf.valueCount()).append(" values \u00B7 Enter or click: all values");
            case MORE -> html.append(cell.moreCount()).append(" more values \u00B7 Enter or click: show all");
            case VALUE -> {
                ValueShare vs = cell.valueShare();
                html.append("<b>").append(escapeHtml(vs.value())).append("</b> (")
                        .append(vs.count()).append(", ").append(vs.sharePercent()).append("%)<br>")
                        .append("Click or Enter: narrow \u00B7 Ctrl/Shift-click, right-click or -: exclude");
            }
        }
        return html.append("</html>").toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            if (rows.isEmpty()) {
                g2.setFont(font);
                g2.setColor(DarculaColors.G6);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(emptyMessage, 10, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                return;
            }

            boolean focused = isFocusOwner();
            List<RowLayout> layouts = layoutRows();
            FontMetrics fm = g2.getFontMetrics(font);

            for (RowLayout rl : layouts) {
                boolean stripe = (rl.row() % 2) == 1;
                boolean rowHover = rl.row() == hoverRow;
                if (rowHover || stripe) {
                    g2.setColor(rowHover ? DarculaColors.LIST_HOVER : DarculaColors.LIST_STRIPE);
                    g2.fillRect(0, rl.y(), getWidth(), ROW_HEIGHT);
                }
                if (FieldSuggestions.isInDraft(rl.field().field(), draft)) {
                    g2.setColor(DarculaColors.ORANGE);
                    g2.fillRect(0, rl.y() + 2, 2, ROW_HEIGHT - 4);
                }

                for (Cell cell : rl.cells()) {
                    boolean cellSelected = focused && rl.row() == selectedRow && cell.col() == selectedCol;
                    boolean cellHovered = cell.equals(hoverCell);
                    paintCell(g2, fm, cell, cellSelected, cellHovered);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    static void paintCell(Graphics2D g2, FontMetrics fm, Cell cell, boolean selected, boolean hovered) {
        Rectangle b = cell.bounds();
        int baseline = b.y + (b.height - fm.getHeight()) / 2 + fm.getAscent();

        switch (cell.kind()) {
            case LABEL -> {
                if (hovered || selected) {
                    g2.setColor(DarculaColors.G3);
                    g2.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, CELL_ARC, CELL_ARC));
                }
                g2.setFont(fm.getFont());
                g2.setColor(hovered || selected ? DarculaColors.FOREGROUND : DarculaColors.G8);
                g2.drawString(cell.displayText(), b.x + CELL_PAD_X, baseline);
            }
            case MORE -> {
                if (hovered || selected) {
                    g2.setColor(DarculaColors.G3);
                    g2.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, CELL_ARC, CELL_ARC));
                }
                g2.setFont(fm.getFont());
                g2.setColor(hovered || selected ? DarculaColors.G8 : DarculaColors.G7);
                g2.drawString(cell.displayText(), b.x + CELL_PAD_X, baseline);
            }
            case VALUE -> {
                if (cell.narrowed()) {
                    g2.setColor(DarculaColors.G3);
                    g2.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, CELL_ARC, CELL_ARC));
                    g2.setColor(DarculaColors.NARROW_ACCENT);
                    g2.draw(new RoundRectangle2D.Float(b.x + 0.5f, b.y + 0.5f, b.width - 1f, b.height - 1f,
                            CELL_ARC, CELL_ARC));
                } else if (cell.excluded()) {
                    g2.setColor(DarculaColors.G2);
                    g2.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, CELL_ARC, CELL_ARC));
                    g2.setColor(DarculaColors.EXCLUDE_ACCENT);
                    g2.draw(new RoundRectangle2D.Float(b.x + 0.5f, b.y + 0.5f, b.width - 1f, b.height - 1f,
                            CELL_ARC, CELL_ARC));
                } else if (hovered) {
                    g2.setColor(DarculaColors.G3);
                    g2.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, CELL_ARC, CELL_ARC));
                }

                g2.setFont(fm.getFont());
                Color valColor = cell.narrowed()
                        ? DarculaColors.NARROW_ACCENT
                        : (cell.excluded() ? DarculaColors.EXCLUDE_ACCENT : DarculaColors.FOREGROUND);
                g2.setColor(valColor);
                int valX = b.x + CELL_PAD_X;
                g2.drawString(cell.displayText(), valX, baseline);
                int valW = fm.stringWidth(cell.displayText());
                if (cell.excluded()) {
                    int strikeY = baseline - fm.getAscent() / 3;
                    g2.drawLine(valX, strikeY, valX + valW, strikeY);
                }

                if (cell.shareText() != null) {
                    g2.setColor(DarculaColors.G6);
                    g2.drawString(cell.shareText(), valX + valW + SHARE_GAP, baseline);
                }
            }
        }

        if (selected) {
            g2.setColor(DarculaColors.ORANGE);
            Stroke old = g2.getStroke();
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(new RoundRectangle2D.Float(b.x + 0.5f, b.y + 0.5f, b.width - 1f, b.height - 1f,
                    CELL_ARC, CELL_ARC));
            g2.setStroke(old);
        }
    }
}
