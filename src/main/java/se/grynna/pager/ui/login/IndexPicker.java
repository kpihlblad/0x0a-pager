package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.IndexSelection;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Picks the indexes a scope browses: one, several or all ({@code *}). The picker is a combo-box-like field
 * showing one chip per selected index (a single {@code all indexes} chip for {@code *}); its popup lists
 * {@code * all indexes} and the known indexes with a check mark each, under a filter field.
 * <p>
 * The value is the {@link IndexSelection#key() selection key}. Ticks in the popup change a <em>pending</em>
 * selection which is committed when the popup closes, so a burst of ticks reloads once; a chip's × commits at
 * once. {@code *} and single indexes exclude each other. A click or Enter ticks (or unticks) an entry and closes
 * the popup; Ctrl-, Shift- or Meta-click and Space tick and keep it open for picking several.
 * <p>
 * All methods run on the EDT.
 */
public class IndexPicker extends JComponent {

    static final String ALL = "*";
    static final int MAX_VISIBLE_ROWS = 12;

    private static final String PLACEHOLDER = "- Select Index -";
    private static final String PLACEHOLDER_LOADING = "Loading...";
    private static final String ALL_CHIP = "all indexes";
    private static final String ALL_ENTRY = "* all indexes";
    private static final String REMOVE_SIGN = "\u00D7";
    private static final String CHECK = "\u2713";
    private static final String UNLISTED_TOOLTIP = "not in the index list";
    private static final String FILTER_PLACEHOLDER = "filter";
    private static final String MULTI_HINT = "Ctrl/Shift+click or Space: pick several";
    private static final float HINT_FONT_SIZE = 11f;

    private static final Dimension PREFERRED_SIZE = new Dimension(420, 28);
    private static final float CHIP_FONT_SIZE = 12f;
    private static final int FIELD_PAD = 6;
    private static final int ARROW_AREA = 24;
    private static final int CHIP_HEIGHT = 20;
    private static final int CHIP_ARC = 8;
    private static final int CHIP_PAD_LEFT = 7;
    private static final int CHIP_PAD_RIGHT = 3;
    private static final int CHIP_GAP = 4;
    private static final int TEXT_GAP = 5;
    private static final int ROW_HEIGHT = 24;
    private static final int POPUP_MIN_WIDTH = 240;
    private static final String CHECK_COLUMN_BLANK = "   ";
    private static final long REOPEN_GUARD_NANOS = 250_000_000L;

    private final Font chipFont = Fontalizer.getFont().deriveFont(CHIP_FONT_SIZE);

    private SortedSet<String> known = new TreeSet<>();
    private boolean loaded;
    private String key = "";
    // Non-null while an edit is open: the items ({@code *} or index names) ticked so far
    private Set<String> pending;
    private Consumer<String> onChange;

    private JPopupMenu popup;
    private PopupContent content;
    private long popupClosedAt;

    public IndexPicker() {
        setFont(Fontalizer.getFont());
        setFocusable(true);
        setOpaque(false);
        ToolTipManager.sharedInstance().registerComponent(this);

        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "open");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "open");
        getActionMap().put("open", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showPopup();
            }
        });

        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                onPressed(e);
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Chip chip = chipAt(e.getPoint());
                boolean overRemove = chip != null && chip.removeBounds().contains(e.getPoint()) && isEnabled();
                setCursor(Cursor.getPredefinedCursor(overRemove ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
    }

    // --- API ---

    /** The known indexes; keeps the selection, even indexes no longer listed, and fires nothing. */
    public void setIndexes(SortedSet<String> indexes) {
        known = indexes == null ? new TreeSet<>() : new TreeSet<>(indexes);
        loaded = true;
        if (content != null) {
            content.refresh();
        }
        repaint();
    }

    /** The selection key: {@code ""} for none, {@code *} for all, else the sorted names joined with {@code ,}. */
    public String getSelectionKey() {
        return key;
    }

    /** Sets the selection (normalized) and fires the change listener when the key changed. */
    public void setSelectionKey(String newKey) {
        String normalized = IndexSelection.parse(newKey).key();
        pending = null;
        closePopup();
        if (normalized.equals(key)) {
            return;
        }
        key = normalized;
        repaint();
        if (onChange != null) {
            onChange.accept(key);
        }
    }

    /** The listener is called with the new key after each committed change. */
    public void setOnChange(Consumer<String> listener) {
        this.onChange = listener;
    }

    /** The number of known indexes, without the {@code *} entry. */
    public int choiceCount() {
        return known.size();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled && popup != null && popup.isVisible()) {
            popup.setVisible(false);
        }
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : new Dimension(PREFERRED_SIZE);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(120, PREFERRED_SIZE.height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Short.MAX_VALUE, PREFERRED_SIZE.height);
    }

    // --- Pending edit (test hooks and popup) ---

    /** Start a pending edit from the current selection. */
    void openPending() {
        pending = new TreeSet<>(selectionItems());
    }

    /** Tick or untick {@code item} ({@code *} or an index name) in the pending edit, opening one if needed. */
    void togglePending(String item) {
        if (pending == null) {
            openPending();
        }
        if (ALL.equals(item)) {
            boolean wasAll = pending.contains(ALL);
            pending.clear();
            if (!wasAll) {
                pending.add(ALL);
            }
        } else if (!pending.remove(item)) {
            pending.remove(ALL);
            pending.add(item);
        }
        if (content != null) {
            content.repaintList();
        }
    }

    /** End the pending edit: the selection becomes the pending one, and the listener fires if the key changed. */
    void commitPending() {
        if (pending == null) {
            return;
        }
        String newKey = IndexSelection.of(pending).key();
        pending = null;
        if (!newKey.equals(key)) {
            key = newKey;
            repaint();
            if (onChange != null) {
                onChange.accept(key);
            }
        }
    }

    /** What the chips show, as laid out at the current width, e.g. {@code web ×} and {@code +2 more}. */
    List<String> chipTexts() {
        return layoutChips().stream().map(Chip::text).toList();
    }

    /** The popup entries for {@code filter}: {@code *} when it fits, then the known indexes containing it. */
    List<String> visibleChoices(String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<String> items = new ArrayList<>();
        if (needle.isEmpty() || ALL.equals(needle) || "all".startsWith(needle)) {
            items.add(ALL);
        }
        for (String name : known) {
            if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                items.add(name);
            }
        }
        return items;
    }

    /** The × of a chip: drops {@code item} ({@code *} or an index name) from the selection and commits at once. */
    void removeChip(String item) {
        List<String> items = new ArrayList<>(selectionItems());
        items.remove(item);
        setSelectionKey(IndexSelection.of(items).key());
    }

    /**
     * The popup's content for a pending edit started from the current selection; the popup shows it, and
     * the snapshot tool renders it standalone. Not committed until the popup closes.
     */
    public JComponent createPopupContent() {
        openPending();
        content = new PopupContent();
        return content;
    }

    private List<String> selectionItems() {
        IndexSelection selection = IndexSelection.parse(key);
        return selection.isAll() ? List.of(ALL) : selection.indexes();
    }

    // --- Popup ---

    private void showPopup() {
        if (!isEnabled() || !isShowing() || (popup != null && popup.isVisible())) {
            return;
        }
        popup = new JPopupMenu();
        popup.setFocusable(true);
        popup.setBorder(BorderFactory.createLineBorder(DarculaColors.G3));
        popup.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        createPopupContent();
        content.width = Math.max(getWidth(), POPUP_MIN_WIDTH);
        popup.add(content);
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                popupClosedAt = System.nanoTime();
                content = null;
                commitPending();
                SwingUtilities.invokeLater(() -> {
                    if (isShowing()) {
                        requestFocusInWindow();
                    }
                });
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        popup.show(this, 0, getHeight());
        SwingUtilities.invokeLater(() -> {
            if (content != null) {
                content.filterField.requestFocusInWindow();
            }
        });
    }

    private void closePopup() {
        if (popup != null && popup.isVisible()) {
            popup.setVisible(false);
        }
    }

    private void onPressed(MouseEvent e) {
        if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        requestFocusInWindow();
        Chip chip = chipAt(e.getPoint());
        if (chip != null && chip.removes() && chip.removeBounds().contains(e.getPoint())) {
            removeChip(chip.item());
            return;
        }
        if (popup != null && popup.isVisible()) {
            closePopup();
        } else if (System.nanoTime() - popupClosedAt > REOPEN_GUARD_NANOS) {
            showPopup();
        }
    }

    // --- Chips ---

    /**
     * One painted chip. {@code item} is the {@code *} or index name it stands for, null for the overflow
     * {@code +N more}; {@code bounds} and {@code removeBounds} (the ×) are in component coordinates.
     */
    private record Chip(String item, String label, boolean dim, Rectangle bounds, Rectangle removeBounds,
            String overflowTooltip) {

        boolean removes() {
            return item != null;
        }

        String text() {
            return item == null ? label : label + " " + REMOVE_SIGN;
        }
    }

    private int layoutWidth() {
        return getWidth() > 0 ? getWidth() : PREFERRED_SIZE.width;
    }

    private int layoutHeight() {
        return getHeight() > 0 ? getHeight() : PREFERRED_SIZE.height;
    }

    private List<Chip> layoutChips() {
        List<String> items = selectionItems();
        if (items.isEmpty()) {
            return List.of();
        }
        FontMetrics fm = getFontMetrics(chipFont);
        int removeWidth = fm.stringWidth(REMOVE_SIGN);
        int room = layoutWidth() - FIELD_PAD - ARROW_AREA;
        int y = (layoutHeight() - CHIP_HEIGHT) / 2;

        List<String> labels = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        for (String item : items) {
            String label = ALL.equals(item) ? ALL_CHIP : item;
            labels.add(label);
            widths.add(CHIP_PAD_LEFT + fm.stringWidth(label) + TEXT_GAP + removeWidth + CHIP_PAD_RIGHT);
        }

        int shown = items.size();
        int used = widths.stream().mapToInt(w -> w + CHIP_GAP).sum() - CHIP_GAP;
        if (used > room) {
            shown = 0;
            used = 0;
            while (shown < items.size()) {
                int with = used + widths.get(shown) + CHIP_GAP;
                int moreWidth = fm.stringWidth(moreLabel(items.size() - shown - 1)) + 2 * CHIP_GAP;
                boolean last = shown == items.size() - 1;
                if (with + (last ? 0 : moreWidth) - CHIP_GAP > room) {
                    break;
                }
                used = with;
                shown++;
            }
        }

        List<Chip> chips = new ArrayList<>();
        int x = FIELD_PAD;
        for (int i = 0; i < shown; i++) {
            String item = items.get(i);
            Rectangle bounds = new Rectangle(x, y, widths.get(i), CHIP_HEIGHT);
            int removeX = x + widths.get(i) - CHIP_PAD_RIGHT - removeWidth - 2;
            Rectangle remove = new Rectangle(removeX, y, removeWidth + CHIP_PAD_RIGHT + 4, CHIP_HEIGHT);
            boolean dim = !ALL.equals(item) && loaded && !known.contains(item);
            chips.add(new Chip(item, labels.get(i), dim, bounds, remove, null));
            x += widths.get(i) + CHIP_GAP;
        }
        if (shown < items.size()) {
            String more = moreLabel(items.size() - shown);
            Rectangle bounds = new Rectangle(x + CHIP_GAP / 2, y, fm.stringWidth(more) + CHIP_GAP, CHIP_HEIGHT);
            chips.add(new Chip(null, more, false, bounds, new Rectangle(), allTooltip(items)));
        }
        return chips;
    }

    private static String moreLabel(int hidden) {
        return "+%d more".formatted(hidden);
    }

    private String allTooltip(List<String> items) {
        StringBuilder html = new StringBuilder("<html>");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                html.append("<br>");
            }
            String item = items.get(i);
            String name = ALL.equals(item) ? ALL_CHIP : item;
            html.append(name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            if (!ALL.equals(item) && loaded && !known.contains(item)) {
                html.append(" (").append(UNLISTED_TOOLTIP).append(')');
            }
        }
        return html.append("</html>").toString();
    }

    private Chip chipAt(Point p) {
        for (Chip chip : layoutChips()) {
            if (chip.bounds().contains(p)) {
                return chip;
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        Chip chip = chipAt(event.getPoint());
        if (chip == null) {
            return null;
        }
        if (chip.overflowTooltip() != null) {
            return chip.overflowTooltip();
        }
        if (chip.removeBounds().contains(event.getPoint())) {
            return "Remove " + chip.label() + " from the scope";
        }
        return chip.dim() ? UNLISTED_TOOLTIP : null;
    }

    // --- Painting ---

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean enabled = isEnabled();
            int w = getWidth();
            int h = getHeight();

            g2.setColor(DarculaColors.BACKGROUND_OUTSIDE);
            g2.fillRect(0, 0, w, h);
            g2.setColor(enabled && isFocusOwner() ? DarculaColors.G7 : DarculaColors.G3);
            g2.drawRect(0, 0, w - 1, h - 1);

            paintArrow(g2, w, h, enabled);

            g2.setClip(1, 1, Math.max(0, w - ARROW_AREA - 1), h - 2);
            g2.setFont(chipFont);
            FontMetrics fm = g2.getFontMetrics();
            List<Chip> chips = layoutChips();
            if (chips.isEmpty()) {
                g2.setColor(DarculaColors.G6);
                g2.drawString(loaded ? PLACEHOLDER : PLACEHOLDER_LOADING, FIELD_PAD + 2,
                        (h - fm.getHeight()) / 2 + fm.getAscent());
            }
            for (Chip chip : chips) {
                paintChip(g2, fm, chip, enabled);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintChip(Graphics2D g2, FontMetrics fm, Chip chip, boolean enabled) {
        Rectangle b = chip.bounds();
        int baseline = b.y + (b.height - fm.getHeight()) / 2 + fm.getAscent();
        if (!chip.removes()) {
            g2.setColor(DarculaColors.G7);
            g2.drawString(chip.label(), b.x + CHIP_GAP / 2, baseline);
            return;
        }
        g2.setColor(enabled ? DarculaColors.G3 : DarculaColors.G2);
        g2.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, CHIP_ARC, CHIP_ARC));
        g2.setColor(!enabled || chip.dim()
                ? DarculaColors.G6
                : (ALL.equals(chip.item()) ? DarculaColors.ORANGE : DarculaColors.G9));
        g2.drawString(chip.label(), b.x + CHIP_PAD_LEFT, baseline);
        g2.setColor(enabled ? DarculaColors.G7 : DarculaColors.G5);
        g2.drawString(REMOVE_SIGN, chip.removeBounds().x + 2, baseline);
    }

    private void paintArrow(Graphics2D g2, int w, int h, boolean enabled) {
        int cx = w - ARROW_AREA / 2 - 1;
        int cy = h / 2;
        g2.setColor(enabled ? DarculaColors.G7 : DarculaColors.G5);
        g2.fillPolygon(new int[]{cx - 4, cx + 4, cx}, new int[]{cy - 2, cy - 2, cy + 3}, 3);
    }

    // --- Popup content ---

    /** The filter field above the check-mark list of {@code *} and the known indexes. */
    private final class PopupContent extends JPanel {

        private final JTextField filterField = new JTextField();
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);
        private int width = 280;

        PopupContent() {
            super(new BorderLayout());
            setBackground(DarculaColors.BACKGROUND_OUTSIDE);

            filterField.setFont(Fontalizer.getFont().deriveFont(CHIP_FONT_SIZE));
            filterField.putClientProperty("JTextField.placeholderText", FILTER_PLACEHOLDER);
            filterField.putClientProperty("JTextField.showClearButton", true);
            filterField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    refresh();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    refresh();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    refresh();
                }
            });
            bindKeys();

            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.setBorder(new EmptyBorder(4, 4, 4, 4));
            top.add(filterField, BorderLayout.CENTER);

            list.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
            list.setFixedCellHeight(ROW_HEIGHT);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new ChoiceRenderer());
            list.setFocusable(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int row = list.locationToIndex(e.getPoint());
                    if (row >= 0 && list.getCellBounds(row, row).contains(e.getPoint())) {
                        list.setSelectedIndex(row);
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    int row = list.locationToIndex(e.getPoint());
                    if (row >= 0 && list.getCellBounds(row, row).contains(e.getPoint())) {
                        list.setSelectedIndex(row);
                        if (e.isControlDown() || e.isShiftDown() || e.isMetaDown()) {
                            toggleCurrent();
                        } else {
                            toggleCurrentAndClose();
                        }
                    }
                }
            };
            list.addMouseListener(mouse);
            list.addMouseMotionListener(mouse);

            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G3));
            scroll.getViewport().setBackground(DarculaColors.BACKGROUND_OUTSIDE);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);

            JLabel hint = new JLabel(MULTI_HINT);
            hint.setFont(Fontalizer.getFont().deriveFont(HINT_FONT_SIZE));
            hint.setForeground(DarculaColors.G6);
            hint.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G3), new EmptyBorder(3, 8, 3, 8)));

            add(top, BorderLayout.NORTH);
            add(scroll, BorderLayout.CENTER);
            add(hint, BorderLayout.SOUTH);
            refresh();
        }

        private void bindKeys() {
            bind(KeyEvent.VK_DOWN, () -> move(1));
            bind(KeyEvent.VK_UP, () -> move(-1));
            bind(KeyEvent.VK_PAGE_DOWN, () -> move(MAX_VISIBLE_ROWS));
            bind(KeyEvent.VK_PAGE_UP, () -> move(-MAX_VISIBLE_ROWS));
            bind(KeyEvent.VK_ENTER, this::toggleCurrentAndClose);
            // Index names have no space; the typed key is bound so the field does not insert it
            bindStroke(KeyStroke.getKeyStroke(' '), this::toggleCurrent);
            bind(KeyEvent.VK_ESCAPE, IndexPicker.this::closePopup);
        }

        private void bind(int keyCode, Runnable action) {
            bindStroke(KeyStroke.getKeyStroke(keyCode, 0), action);
        }

        private void bindStroke(KeyStroke stroke, Runnable action) {
            String name = "picker-" + stroke;
            filterField.getInputMap(WHEN_FOCUSED).put(stroke, name);
            filterField.getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    action.run();
                }
            });
        }

        void refresh() {
            String current = list.getSelectedValue();
            model.clear();
            visibleChoices(filterField.getText()).forEach(model::addElement);
            int row = current == null ? -1 : model.indexOf(current);
            if (row < 0 && !model.isEmpty()) {
                row = 0;
            }
            if (row >= 0) {
                list.setSelectedIndex(row);
            }
            if (popup != null && popup.isVisible()) {
                popup.pack();
            }
            revalidate();
            repaint();
        }

        void repaintList() {
            list.repaint();
        }

        private void move(int delta) {
            if (model.isEmpty()) {
                return;
            }
            int row = Math.clamp(list.getSelectedIndex() + delta, 0, model.size() - 1);
            list.setSelectedIndex(row);
            list.ensureIndexIsVisible(row);
        }

        private void toggleCurrent() {
            String item = list.getSelectedValue();
            if (item != null) {
                togglePending(item);
            }
        }

        /** Tick or untick the current entry and close the popup, which commits the selection. */
        private void toggleCurrentAndClose() {
            if (list.getSelectedValue() != null) {
                toggleCurrent();
                closePopup();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            int rows = Math.clamp(model.size(), 1, MAX_VISIBLE_ROWS);
            int top = filterField.getPreferredSize().height + 8;
            int hint = ((JComponent) getComponent(2)).getPreferredSize().height;
            return new Dimension(width, top + 1 + rows * ROW_HEIGHT + hint);
        }
    }

    /** A check mark (amber when ticked) before the entry; the {@code *} entry reads {@code * all indexes}. */
    private final class ChoiceRenderer extends JLabel implements ListCellRenderer<String> {

        private boolean ticked;

        ChoiceRenderer() {
            setOpaque(true);
            setFont(Fontalizer.getFont().deriveFont(CHIP_FONT_SIZE));
            setBorder(new EmptyBorder(0, 8, 0, 8));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String item, int row,
                boolean selected, boolean focus) {
            ticked = pending != null && pending.contains(item);
            setText(CHECK_COLUMN_BLANK + (ALL.equals(item) ? ALL_ENTRY : item));
            setBackground(selected ? DarculaColors.CURRENT_LINE : DarculaColors.BACKGROUND_OUTSIDE);
            setForeground(ALL.equals(item) ? DarculaColors.G8 : DarculaColors.FOREGROUND);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (ticked) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(DarculaColors.ORANGE);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(CHECK, 8, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        }
    }
}
