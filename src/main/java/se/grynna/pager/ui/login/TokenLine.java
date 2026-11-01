package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SplQuery;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.login.FieldSuggestions.SuggestedField;
import se.grynna.pager.ui.login.TermCompleter.Entry;
import se.grynna.pager.ui.login.TermCompleter.FieldEntry;
import se.grynna.pager.ui.login.TermCompleter.Mark;
import se.grynna.pager.ui.login.TermCompleter.TextEntry;
import se.grynna.pager.ui.login.TermCompleter.ValueEntry;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * One token line of the scope selector ({@code NARROW} or {@code EXCLUDE}): a tracked section label, the active
 * field-value and text chips (with a dim {@code or} between ORed values of the same field), and an inline text
 * input with a non-focusable completion popup.
 */
public final class TokenLine extends JPanel {

    private static final int BAR_WIDTH = 2;
    private static final int LABEL_INSET_LEFT = 14;
    private static final int LABEL_COLUMN_WIDTH = 96;
    private static final float HEADING_SIZE = 12f;
    private static final float CHIP_FONT_SIZE = 12f;
    private static final int LINE_HEIGHT = 24;
    private static final int CHIP_HEIGHT = 20;
    private static final int CHIP_ARC = 8;
    private static final int CHIP_PAD_LEFT = 7;
    private static final int CHIP_PAD_RIGHT = 5;
    private static final int CHIP_GAP = 6;
    private static final int LINE_VGAP = 4;
    private static final int BOX_PAD_X = 6;
    private static final int BOX_PAD_Y = 3;
    private static final int MIN_INPUT_WIDTH = 180;
    private static final int POPUP_ROW_HEIGHT = 24;
    private static final int POPUP_MIN_WIDTH = 320;
    private static final int POPUP_MAX_ROWS = 10;
    private static final String REMOVE_SIGN = "\u00D7";
    private static final String OR_TEXT = "or";

    /** One chip displayed on the line. */
    public record Chip(boolean fieldChip, String fieldKey, String label, String value, String rawTerm,
            boolean precededByOr, String tooltip) {

        public String displayText() {
            return fieldChip ? label + "=" + value : "\"" + value + "\"";
        }

        public String text() {
            return displayText() + " " + REMOVE_SIGN;
        }

        public String editText() {
            return fieldChip ? label + "=" + value : value;
        }
    }

    private record PaintedChip(int index, Chip chip, Rectangle bounds, Rectangle removeBounds, Point orPosition) {}

    private final boolean narrow;
    private final UnaryOperator<Scope> scopeEditor;
    private final Font font = Fontalizer.getFont();
    private final Font chipFont = font.deriveFont(CHIP_FONT_SIZE);
    private final JLabel titleLabel;
    private final TokenBox tokenBox;
    private final JTextField inputField;

    private Scope draft = Scope.ofValues(null, Map.of());
    private List<SuggestedField> fields = List.of();
    private List<Chip> chips = List.of();
    private int selectedChipIndex = -1;
    private boolean updatingInput;
    private boolean tabArmed;

    private boolean popupOpen;
    private List<Entry> completions = List.of();
    private int selectedCompletionIndex = 0;
    private JPopupMenu popupMenu;
    private DefaultListModel<Entry> popupModel;
    private JList<Entry> popupList;

    private Runnable onMoveDown;
    private Runnable onMoveUp;
    private Runnable onOpenAllFields;
    private Runnable onCancel;

    public TokenLine(String title, String placeholder, boolean narrow, UnaryOperator<Scope> scopeEditor) {
        super(new BorderLayout(8, 0));
        this.narrow = narrow;
        this.scopeEditor = scopeEditor;
        setOpaque(false);
        setBorder(new EmptyBorder(3, 0, 3, 0));

        Font titleFont = Fontalizer.getUiSemiboldFont().deriveFont(HEADING_SIZE)
                .deriveFont(Map.of(TextAttribute.TRACKING, 0.12f));
        titleLabel = new JLabel(title.toUpperCase());
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(DarculaColors.G7);
        titleLabel.setBorder(new EmptyBorder(0, LABEL_INSET_LEFT, 0, 6));
        titleLabel.setPreferredSize(new Dimension(LABEL_COLUMN_WIDTH, LINE_HEIGHT + 2 * BOX_PAD_Y));
        titleLabel.setVerticalAlignment(SwingConstants.TOP);

        JPanel labelHost = new JPanel(new BorderLayout());
        labelHost.setOpaque(false);
        labelHost.setBorder(new EmptyBorder(5, 0, 0, 0));
        labelHost.add(titleLabel, BorderLayout.NORTH);

        inputField = new JTextField();
        inputField.setFont(chipFont);
        inputField.setForeground(DarculaColors.FOREGROUND);
        inputField.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        inputField.setCaretColor(DarculaColors.CARET);
        inputField.setBorder(new EmptyBorder(0, 2, 0, 2));
        inputField.setOpaque(false);
        inputField.putClientProperty("JTextField.placeholderText", placeholder);
        inputField.setFocusTraversalKeysEnabled(false);

        tokenBox = new TokenBox();
        tokenBox.add(inputField);

        add(labelHost, BorderLayout.WEST);
        add(tokenBox, BorderLayout.CENTER);

        setupInputListeners();
    }

    public void setOnMoveDown(Runnable onMoveDown) {
        this.onMoveDown = onMoveDown;
    }

    public void setOnMoveUp(Runnable onMoveUp) {
        this.onMoveUp = onMoveUp;
    }

    public void setOnOpenAllFields(Runnable onOpenAllFields) {
        this.onOpenAllFields = onOpenAllFields;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** Updates the line's chips and completion context from the current draft and ranked fields. */
    public void updateState(Scope newDraft, List<SuggestedField> newFields) {
        this.draft = newDraft == null ? Scope.ofValues(null, Map.of()) : newDraft;
        this.fields = newFields == null ? List.of() : newFields;
        this.chips = buildChips();
        if (selectedChipIndex >= chips.size()) {
            selectedChipIndex = chips.size() - 1;
        }
        if (popupOpen) {
            refreshCompletions(true);
        }
        tokenBox.revalidate();
        repaint();
    }

    /** Focuses the line's text input field. */
    public void focusInput() {
        selectedChipIndex = -1;
        inputField.requestFocusInWindow();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (inputField.isFocusOwner()) {
            Insets in = getInsets();
            g.setColor(DarculaColors.ORANGE);
            g.fillRect(0, in.top, BAR_WIDTH, getHeight() - in.top - in.bottom);
        }
    }

    // --- Package-private test hooks ---

    List<Chip> chips() {
        return chips;
    }

    List<String> chipTexts() {
        return chips.stream().map(Chip::text).toList();
    }

    /** Rendered sequence including {@code "or"} connectors between ORed values of the same field. */
    List<String> renderedTokens() {
        List<String> out = new ArrayList<>();
        for (Chip chip : chips) {
            if (chip.precededByOr()) {
                out.add(OR_TEXT);
            }
            out.add(chip.text());
        }
        return out;
    }

    String inputText() {
        return inputField.getText();
    }

    void setInputText(String text) {
        selectedChipIndex = -1;
        tabArmed = false;
        inputField.setText(text == null ? "" : text);
        inputField.setCaretPosition(inputField.getText().length());
    }

    List<Entry> completions() {
        return completions;
    }

    boolean isCompletionPopupOpen() {
        return popupOpen;
    }

    int selectedCompletionIndex() {
        return selectedCompletionIndex;
    }

    int selectedChipIndex() {
        return selectedChipIndex;
    }

    JTextField inputField() {
        return inputField;
    }

    void pressKey(int keyCode, int modifiers) {
        ActionMap am = inputField.getActionMap();
        InputMap im = inputField.getInputMap(WHEN_FOCUSED);
        Object key = im.get(KeyStroke.getKeyStroke(keyCode, modifiers));
        if (key != null && am.get(key) != null) {
            am.get(key).actionPerformed(new ActionEvent(inputField, ActionEvent.ACTION_PERFORMED, key.toString()));
        }
    }

    void acceptCompletion() {
        handleEnter();
    }

    void backspace() {
        handleBackspace();
    }

    // --- Internal logic ---

    private void setupInputListeners() {
        inputField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onTextChanged();
            }
        });

        inputField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                titleLabel.setForeground(DarculaColors.ORANGE);
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                titleLabel.setForeground(DarculaColors.G7);
                selectedChipIndex = -1;
                tabArmed = false;
                closeCompletionPopup();
                repaint();
            }
        });

        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (!Character.isISOControl(c)) {
                    selectedChipIndex = -1;
                    tabArmed = false;
                }
            }
        });

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "token-backspace", this::handleBackspace);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "token-delete", this::handleDelete);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "token-left", this::handleLeft);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "token-right", this::handleRight);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "token-enter", this::handleEnter);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "token-tab", this::handleTab);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "token-down", this::handleDown);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "token-up", this::handleUp);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "token-escape", this::handleEscape);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "token-all-fields-alt",
                this::openAllFields);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                "token-all-fields-emacs", this::openAllFields);
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        inputField.getInputMap(WHEN_FOCUSED).put(stroke, name);
        inputField.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void openAllFields() {
        closeCompletionPopup();
        if (onOpenAllFields != null) {
            onOpenAllFields.run();
        }
    }

    private void onTextChanged() {
        if (updatingInput) {
            return;
        }
        selectedChipIndex = -1;
        tabArmed = false;
        String text = inputField.getText();
        if (text == null || text.isBlank()) {
            completions = TermCompleter.completeFromFields("", fields, draft);
            selectedCompletionIndex = 0;
            closeCompletionPopup();
        } else {
            refreshCompletions(true);
        }
        tokenBox.revalidate();
        repaint();
    }

    private void refreshCompletions(boolean open) {
        String raw = inputField.getText();
        String query = narrow ? TermCompleter.stripExcludePrefix(raw) : (raw == null ? "" : raw.strip());
        completions = TermCompleter.completeFromFields(query, fields, draft);
        if (completions.isEmpty()) {
            selectedCompletionIndex = 0;
            closeCompletionPopup();
            return;
        }
        selectedCompletionIndex = Math.clamp(selectedCompletionIndex, 0, completions.size() - 1);
        if (open) {
            popupOpen = true;
            syncSwingPopup();
        }
    }

    private void handleBackspace() {
        tabArmed = false;
        if (selectedChipIndex >= 0 && selectedChipIndex < chips.size()) {
            Chip target = chips.get(selectedChipIndex);
            selectedChipIndex = -1;
            removeChip(target);
            return;
        }
        String text = inputField.getText();
        if ((text == null || text.isEmpty()) && !chips.isEmpty()) {
            selectedChipIndex = chips.size() - 1;
            repaint();
            return;
        }
        Action defaultBackspace = inputField.getActionMap().get("delete-previous");
        if (defaultBackspace != null) {
            defaultBackspace.actionPerformed(new ActionEvent(inputField, ActionEvent.ACTION_PERFORMED, "backspace"));
        }
    }

    private void handleDelete() {
        tabArmed = false;
        if (selectedChipIndex >= 0 && selectedChipIndex < chips.size()) {
            Chip target = chips.get(selectedChipIndex);
            selectedChipIndex = -1;
            removeChip(target);
            return;
        }
        Action defaultDelete = inputField.getActionMap().get("delete-next");
        if (defaultDelete != null) {
            defaultDelete.actionPerformed(new ActionEvent(inputField, ActionEvent.ACTION_PERFORMED, "delete"));
        }
    }

    private void handleLeft() {
        tabArmed = false;
        if (selectedChipIndex > 0) {
            selectedChipIndex--;
            repaint();
            return;
        }
        if (selectedChipIndex == 0) {
            return;
        }
        if (inputField.getCaretPosition() == 0
                && inputField.getSelectionStart() == inputField.getSelectionEnd()
                && !chips.isEmpty()) {
            selectedChipIndex = chips.size() - 1;
            repaint();
            return;
        }
        Action defaultLeft = inputField.getActionMap().get("caret-backward");
        if (defaultLeft != null) {
            defaultLeft.actionPerformed(new ActionEvent(inputField, ActionEvent.ACTION_PERFORMED, "left"));
        }
    }

    private void handleRight() {
        tabArmed = false;
        if (selectedChipIndex >= 0) {
            if (selectedChipIndex < chips.size() - 1) {
                selectedChipIndex++;
            } else {
                selectedChipIndex = -1;
                inputField.setCaretPosition(0);
            }
            repaint();
            return;
        }
        Action defaultRight = inputField.getActionMap().get("caret-forward");
        if (defaultRight != null) {
            defaultRight.actionPerformed(new ActionEvent(inputField, ActionEvent.ACTION_PERFORMED, "right"));
        }
    }

    private void handleEnter() {
        tabArmed = false;
        if (selectedChipIndex >= 0 && selectedChipIndex < chips.size()) {
            Chip chip = chips.get(selectedChipIndex);
            selectedChipIndex = -1;
            removeChip(chip);
            setInputFieldQuietly(chip.editText());
            refreshCompletions(true);
            return;
        }
        if (popupOpen && !completions.isEmpty()) {
            acceptEntry(completions.get(selectedCompletionIndex));
            return;
        }
        String raw = inputField.getText();
        if (raw != null && !raw.isBlank()) {
            refreshCompletions(false);
            if (!completions.isEmpty()) {
                acceptEntry(completions.getFirst());
            }
        }
    }

    private void handleTab() {
        if (popupOpen && !completions.isEmpty()) {
            tabArmed = false;
            acceptEntry(completions.get(selectedCompletionIndex));
            return;
        }
        String raw = inputField.getText();
        if (raw != null && !raw.isBlank()) {
            tabArmed = false;
            refreshCompletions(false);
            if (!completions.isEmpty()) {
                acceptEntry(completions.getFirst());
            }
            return;
        }
        if (tabArmed) {
            tabArmed = false;
            openAllFields();
        } else {
            tabArmed = true;
        }
    }

    private void handleDown() {
        tabArmed = false;
        if (popupOpen && !completions.isEmpty()) {
            selectedCompletionIndex = Math.clamp(selectedCompletionIndex + 1, 0, completions.size() - 1);
            if (popupList != null) {
                popupList.setSelectedIndex(selectedCompletionIndex);
                popupList.ensureIndexIsVisible(selectedCompletionIndex);
            }
            return;
        }
        if (onMoveDown != null) {
            onMoveDown.run();
        }
    }

    private void handleUp() {
        tabArmed = false;
        if (popupOpen && !completions.isEmpty()) {
            selectedCompletionIndex = Math.clamp(selectedCompletionIndex - 1, 0, completions.size() - 1);
            if (popupList != null) {
                popupList.setSelectedIndex(selectedCompletionIndex);
                popupList.ensureIndexIsVisible(selectedCompletionIndex);
            }
            return;
        }
        if (onMoveUp != null) {
            onMoveUp.run();
        }
    }

    private void handleEscape() {
        tabArmed = false;
        if (popupOpen) {
            closeCompletionPopup();
            return;
        }
        if (selectedChipIndex >= 0) {
            selectedChipIndex = -1;
            repaint();
            return;
        }
        if (onCancel != null) {
            onCancel.run();
        }
    }

    private void acceptEntry(Entry entry) {
        String raw = inputField.getText();
        boolean excludePrefix = narrow && TermCompleter.hasExcludePrefix(raw);
        boolean asExclude = !narrow || excludePrefix;

        switch (entry) {
            case FieldEntry fe -> {
                String prefix = excludePrefix ? raw.stripLeading().substring(0, 1) : "";
                setInputFieldQuietly(prefix + fe.label() + "=");
                selectedCompletionIndex = 0;
                refreshCompletions(true);
            }
            case ValueEntry ve -> {
                setInputFieldQuietly("");
                closeCompletionPopup();
                if (asExclude) {
                    scopeEditor.apply(FieldSuggestions.withExcludeFieldValue(draft, ve.field(), ve.value()));
                } else if (ve.mark() != Mark.NARROWED) {
                    scopeEditor.apply(FieldSuggestions.withNarrowFieldValue(draft, ve.field(), ve.value()));
                }
            }
            case TextEntry te -> {
                setInputFieldQuietly("");
                closeCompletionPopup();
                if (asExclude) {
                    if (Scope.isExcludable(te.text())) {
                        scopeEditor.apply(draft.withExclude(te.text()));
                    }
                } else {
                    scopeEditor.apply(draft.withNarrow(te.text()));
                }
            }
        }
    }

    private void setInputFieldQuietly(String text) {
        updatingInput = true;
        try {
            inputField.setText(text);
            inputField.setCaretPosition(text.length());
        } finally {
            updatingInput = false;
        }
        tokenBox.revalidate();
        repaint();
    }

    void removeChip(Chip chip) {
        if (narrow) {
            if (chip.fieldChip() && chip.rawTerm() == null) {
                scopeEditor.apply(draft.withoutFieldValue(chip.fieldKey(), chip.value()));
            } else {
                scopeEditor.apply(draft.without(chip.rawTerm()));
            }
        } else {
            scopeEditor.apply(draft.without(chip.rawTerm()));
        }
    }

    private List<Chip> buildChips() {
        Map<String, SuggestedField> byName = new HashMap<>();
        for (SuggestedField sf : fields) {
            for (String f : sf.field().fields()) {
                byName.put(f, sf);
            }
        }
        List<Chip> result = new ArrayList<>();
        if (narrow) {
            draft.fieldFilters().forEach((fieldKey, values) -> {
                SuggestedField sf = byName.get(fieldKey);
                String label = sf != null ? sf.label() : fieldKey;
                List<String> realFields = draft.filterFields(fieldKey);
                for (int i = 0; i < values.size(); i++) {
                    String val = values.get(i);
                    String fragment = SplQuery.fieldEqualsAny(realFields, val);
                    String tip = fieldTooltip(realFields, fragment);
                    result.add(new Chip(true, fieldKey, label, val, null, i > 0, tip));
                }
            });
            for (String term : draft.narrow()) {
                Optional<FieldTerm> ft = FieldTerm.decode(term);
                if (ft.isPresent()) {
                    String f = ft.get().field();
                    SuggestedField sf = byName.get(f);
                    String label = sf != null ? sf.label() : f;
                    String tip = fieldTooltip(List.of(f), ft.get().spl());
                    result.add(new Chip(true, f, label, ft.get().value(), term, false, tip));
                } else {
                    result.add(new Chip(false, null, null, term, term, false, "+ \"" + term + "\""));
                }
            }
        } else {
            for (String term : draft.exclude()) {
                Optional<FieldTerm> ft = FieldTerm.decode(term);
                if (ft.isPresent()) {
                    String f = ft.get().field();
                    SuggestedField sf = byName.get(f);
                    String label = sf != null ? sf.label() : f;
                    List<String> realFields = sf != null ? sf.field().fields() : List.of(f);
                    String tip = fieldTooltip(realFields, "NOT " + ft.get().spl());
                    result.add(new Chip(true, f, label, ft.get().value(), term, false, tip));
                } else {
                    result.add(new Chip(false, null, null, term, term, false, "\u2212 \"" + term + "\""));
                }
            }
        }
        return List.copyOf(result);
    }

    private static String fieldTooltip(List<String> realFields, String queryFragment) {
        StringBuilder html = new StringBuilder("<html>");
        for (String name : realFields) {
            html.append(escapeHtml(name)).append("<br>");
        }
        html.append("<code>").append(escapeHtml(queryFragment)).append("</code></html>");
        return html.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // --- Completion Popup ---

    private void syncSwingPopup() {
        if (!isShowing()) {
            return;
        }
        if (popupMenu == null) {
            popupMenu = new JPopupMenu();
            popupMenu.setFocusable(false);
            popupMenu.setBorder(BorderFactory.createLineBorder(DarculaColors.G3));
            popupMenu.setBackground(DarculaColors.BACKGROUND_OUTSIDE);

            popupModel = new DefaultListModel<>();
            popupList = new JList<>(popupModel);
            popupList.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
            popupList.setFixedCellHeight(POPUP_ROW_HEIGHT);
            popupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            popupList.setCellRenderer(new CompletionRenderer());
            popupList.setFocusable(false);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int row = popupList.locationToIndex(e.getPoint());
                    if (row >= 0) {
                        selectedCompletionIndex = row;
                        popupList.setSelectedIndex(row);
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    int row = popupList.locationToIndex(e.getPoint());
                    if (row >= 0 && row < completions.size()) {
                        selectedCompletionIndex = row;
                        acceptEntry(completions.get(row));
                    }
                }
            };
            popupList.addMouseListener(mouse);
            popupList.addMouseMotionListener(mouse);

            JScrollPane scroll = new JScrollPane(popupList);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getViewport().setBackground(DarculaColors.BACKGROUND_OUTSIDE);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            popupMenu.add(scroll);
        }

        popupModel.clear();
        completions.forEach(popupModel::addElement);
        popupList.setSelectedIndex(selectedCompletionIndex);
        popupList.ensureIndexIsVisible(selectedCompletionIndex);

        int rows = Math.clamp(completions.size(), 1, POPUP_MAX_ROWS);
        int width = Math.max(POPUP_MIN_WIDTH, Math.min(tokenBox.getWidth(), 480));
        popupMenu.setPopupSize(width, rows * POPUP_ROW_HEIGHT + 2);
        int x = Math.max(0, Math.min(inputField.getX(), tokenBox.getWidth() - width));
        popupMenu.show(tokenBox, x, tokenBox.getHeight());
    }

    private void closeCompletionPopup() {
        popupOpen = false;
        if (popupMenu != null && popupMenu.isVisible()) {
            popupMenu.setVisible(false);
        }
    }

    private final class CompletionRenderer extends JPanel implements ListCellRenderer<Entry> {

        private final JLabel leftLabel = new JLabel();
        private final JLabel rightLabel = new JLabel();

        CompletionRenderer() {
            super(new BorderLayout(8, 0));
            setOpaque(true);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            leftLabel.setFont(chipFont);
            rightLabel.setFont(chipFont);
            add(leftLabel, BorderLayout.CENTER);
            add(rightLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry, int index,
                boolean isSelected, boolean cellHasFocus) {
            setBackground(isSelected ? DarculaColors.CURRENT_LINE : DarculaColors.BACKGROUND_OUTSIDE);
            switch (entry) {
                case FieldEntry fe -> {
                    leftLabel.setText(fe.label());
                    leftLabel.setForeground(DarculaColors.FOREGROUND);
                    rightLabel.setText(fe.valueCount() + " values");
                    rightLabel.setForeground(DarculaColors.G6);
                }
                case ValueEntry ve -> {
                    String prefix = ve.mark() == Mark.NONE ? "" : ve.mark().symbol() + " ";
                    String text = ve.typedValue()
                            ? prefix + ve.label() + "=" + ve.value()
                            : prefix + ve.label() + " = " + ve.value();
                    leftLabel.setText(text);
                    leftLabel.setForeground(switch (ve.mark()) {
                        case NARROWED -> DarculaColors.NARROW_ACCENT;
                        case EXCLUDED -> DarculaColors.EXCLUDE_ACCENT;
                        case NONE -> DarculaColors.FOREGROUND;
                    });
                    rightLabel.setText(ve.typedValue() ? "typed" : ve.sharePercent() + "%");
                    rightLabel.setForeground(DarculaColors.G6);
                }
                case TextEntry te -> {
                    String prefix = te.mark() == Mark.NONE ? "" : te.mark().symbol() + " ";
                    leftLabel.setText(prefix + "\"" + te.text() + "\"");
                    leftLabel.setForeground(DarculaColors.ORANGE);
                    rightLabel.setText("search text");
                    rightLabel.setForeground(DarculaColors.G6);
                }
            }
            return this;
        }
    }

    // --- Painted Token Box with Wrapping Layout ---

    private final class TokenBox extends JPanel {

        TokenBox() {
            super(null);
            setOpaque(false);
            ToolTipManager.sharedInstance().registerComponent(this);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    PaintedChip hit = chipAt(e.getPoint());
                    if (hit != null) {
                        if (hit.removeBounds().contains(e.getPoint())) {
                            selectedChipIndex = -1;
                            removeChip(hit.chip());
                            inputField.requestFocusInWindow();
                        } else {
                            selectedChipIndex = hit.index();
                            inputField.requestFocusInWindow();
                            repaint();
                        }
                        return;
                    }
                    focusInput();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    PaintedChip hit = chipAt(e.getPoint());
                    boolean hand = hit != null && hit.removeBounds().contains(e.getPoint());
                    setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private int effectiveWidth() {
            if (getWidth() > 0) {
                return getWidth();
            }
            if (TokenLine.this.getWidth() > LABEL_COLUMN_WIDTH + 8) {
                return TokenLine.this.getWidth() - LABEL_COLUMN_WIDTH - 8;
            }
            Container p = TokenLine.this.getParent();
            while (p != null) {
                if (p.getWidth() > LABEL_COLUMN_WIDTH + 60) {
                    return p.getWidth() - LABEL_COLUMN_WIDTH - 56;
                }
                p = p.getParent();
            }
            return 1080;
        }

        private List<PaintedChip> layoutChips(int availWidth, int[] outInputBounds) {
            FontMetrics fm = getFontMetrics(chipFont);
            int removeW = fm.stringWidth(REMOVE_SIGN);
            int orW = fm.stringWidth(OR_TEXT);
            int maxX = Math.max(MIN_INPUT_WIDTH, availWidth - BOX_PAD_X);

            int x = BOX_PAD_X;
            int y = BOX_PAD_Y;
            List<PaintedChip> painted = new ArrayList<>(chips.size());

            for (int i = 0; i < chips.size(); i++) {
                Chip chip = chips.get(i);
                int orAdvance = chip.precededByOr() ? orW + CHIP_GAP : 0;
                int labelW = fm.stringWidth(chip.displayText());
                int chipW = CHIP_PAD_LEFT + labelW + 5 + removeW + CHIP_PAD_RIGHT;

                if (x > BOX_PAD_X && x + orAdvance + chipW > maxX) {
                    x = BOX_PAD_X;
                    y += LINE_HEIGHT + LINE_VGAP;
                }

                Point orPos = null;
                if (chip.precededByOr()) {
                    int baseline = y + (LINE_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
                    orPos = new Point(x, baseline);
                    x += orW + CHIP_GAP;
                }

                int chipY = y + (LINE_HEIGHT - CHIP_HEIGHT) / 2;
                Rectangle bounds = new Rectangle(x, chipY, chipW, CHIP_HEIGHT);
                int removeX = x + chipW - CHIP_PAD_RIGHT - removeW - 2;
                Rectangle remove = new Rectangle(removeX, chipY, removeW + CHIP_PAD_RIGHT + 2, CHIP_HEIGHT);
                painted.add(new PaintedChip(i, chip, bounds, remove, orPos));

                x += chipW + CHIP_GAP;
            }

            if (x > BOX_PAD_X && maxX - x < MIN_INPUT_WIDTH) {
                x = BOX_PAD_X;
                y += LINE_HEIGHT + LINE_VGAP;
            }
            if (outInputBounds != null) {
                outInputBounds[0] = x;
                outInputBounds[1] = y;
                outInputBounds[2] = Math.max(80, maxX - x);
                outInputBounds[3] = LINE_HEIGHT;
            }
            return painted;
        }

        @Override
        public void doLayout() {
            int[] inputRect = new int[4];
            layoutChips(effectiveWidth(), inputRect);
            inputField.setBounds(inputRect[0], inputRect[1], inputRect[2], inputRect[3]);
        }

        @Override
        public Dimension getPreferredSize() {
            int[] inputRect = new int[4];
            layoutChips(effectiveWidth(), inputRect);
            int totalHeight = inputRect[1] + inputRect[3] + BOX_PAD_Y;
            return new Dimension(400, Math.max(LINE_HEIGHT + 2 * BOX_PAD_Y, totalHeight));
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(200, LINE_HEIGHT + 2 * BOX_PAD_Y);
        }

        private PaintedChip chipAt(Point p) {
            for (PaintedChip pc : layoutChips(effectiveWidth(), null)) {
                if (pc.bounds().contains(p)) {
                    return pc;
                }
            }
            return null;
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            PaintedChip pc = chipAt(event.getPoint());
            if (pc == null) {
                return null;
            }
            if (pc.removeBounds().contains(event.getPoint())) {
                return "Remove " + pc.chip().displayText() + " from the scope";
            }
            return pc.chip().tooltip();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                g2.setColor(DarculaColors.BACKGROUND_OUTSIDE);
                g2.fillRect(0, 0, w, h);
                g2.setColor(inputField.isFocusOwner() ? DarculaColors.G7 : DarculaColors.G3);
                g2.drawRect(0, 0, w - 1, h - 1);

                g2.setFont(chipFont);
                FontMetrics fm = g2.getFontMetrics();
                Color chipAccent = narrow ? DarculaColors.NARROW_ACCENT : DarculaColors.EXCLUDE_ACCENT;

                for (PaintedChip pc : layoutChips(effectiveWidth(), null)) {
                    if (pc.orPosition() != null) {
                        g2.setColor(DarculaColors.G6);
                        g2.drawString(OR_TEXT, pc.orPosition().x, pc.orPosition().y);
                    }
                    Rectangle b = pc.bounds();
                    boolean selected = pc.index() == selectedChipIndex;
                    g2.setColor(selected ? DarculaColors.CURRENT_LINE : DarculaColors.G3);
                    g2.fill(new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, CHIP_ARC, CHIP_ARC));
                    if (selected) {
                        g2.setColor(DarculaColors.ORANGE);
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.draw(new RoundRectangle2D.Float(b.x + 0.5f, b.y + 0.5f, b.width - 1f, b.height - 1f,
                                CHIP_ARC, CHIP_ARC));
                        g2.setStroke(new BasicStroke(1f));
                    }

                    int baseline = b.y + (b.height - fm.getHeight()) / 2 + fm.getAscent();
                    g2.setColor(chipAccent);
                    g2.drawString(pc.chip().displayText(), b.x + CHIP_PAD_LEFT, baseline);
                    g2.setColor(DarculaColors.G7);
                    g2.drawString(REMOVE_SIGN, pc.removeBounds().x + 2, baseline);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
