package se.grynna.pager.ui.autocomplete;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Autocomplete document for JComboBox with substring matching support.
 * <p>
 * Key principle: while user types, text is never auto-replaced. The dropdown
 * shows matches but selection only happens when user explicitly confirms
 * (Enter/Tab) or navigates with arrow keys.
 * <p>
 * Two modes of operation:
 * <ul>
 *   <li><b>Selection mode (allowCustomValues=false)</b>: User must select from list.
 *       If no match on confirm, beeps and restores to first item.</li>
 *   <li><b>Custom mode (allowCustomValues=true)</b>: User can type custom values.</li>
 * </ul>
 */
@Slf4j
public class AutoCompletion2 extends PlainDocument {

    private final JComboBox comboBox;
    private JTextComponent editor;

    // Flag to prevent recursive updates when we're programmatically changing things
    private boolean updating = false;

    // Flag to track when we're in findMatch - allows selection change but prevents text change
    private boolean matching = false;

    // Store all items for filtering
    private final List<Object> allItems = new ArrayList<>();

    // If true, user can enter custom values not in the list
    private final boolean allowCustomValues;

    // Track the current best match (without changing combo selection during typing)
    private Object currentMatch = null;

    private final KeyListener editorKeyListener;
    private final FocusListener editorFocusListener;

    public AutoCompletion2(final JComboBox comboBox, boolean allowCustomValues) {
        this.comboBox = comboBox;
        this.allowCustomValues = allowCustomValues;

        // Cache all items for filtering
        ComboBoxModel model = comboBox.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            allItems.add(model.getElementAt(i));
        }

        editorKeyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!comboBox.isDisplayable()) return;

                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ENTER -> {
                        // Confirm selection
                        confirmSelection();
                        e.consume();
                    }
                    case KeyEvent.VK_TAB -> {
                        // Confirm selection, don't consume (let tab move focus)
                        confirmSelection();
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        // Cancel - hide popup
                        comboBox.setPopupVisible(false);
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        navigateSelection(-1);
                        e.consume();
                    }
                    case KeyEvent.VK_DOWN -> {
                        if (!comboBox.isPopupVisible()) {
                            comboBox.setPopupVisible(true);
                        }
                        navigateSelection(1);
                        e.consume();
                    }
                    default -> {
                        // Show popup when typing
                        if (!comboBox.isPopupVisible() && isPrintableChar(e)) {
                            comboBox.setPopupVisible(true);
                        }
                    }
                }
            }
        };

        editorFocusListener = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                // Select all text when gaining focus for easy replacement
                if (editor != null) {
                    editor.selectAll();
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                // When losing focus, confirm the selection
                if (!e.isTemporary()) {
                    confirmSelection();
                }
            }
        };

        configureEditor(comboBox.getEditor());

        // Initialize with current selection
        Object selected = comboBox.getSelectedItem();
        if (selected != null) {
            currentMatch = selected;
            setTextQuietly(selected.toString());
        }
    }

    /**
     * Enable autocomplete with substring matching. User must select from list.
     */
    public static void enable(JComboBox comboBox) {
        enable(comboBox, false);
    }

    /**
     * Enable autocomplete with substring matching.
     * @param allowCustomValues if true, user can enter values not in the list
     */
    public static void enable(JComboBox comboBox, boolean allowCustomValues) {
        comboBox.setEditable(true);
        new AutoCompletion2(comboBox, allowCustomValues);
    }

    private void configureEditor(ComboBoxEditor newEditor) {
        if (editor != null) {
            editor.removeKeyListener(editorKeyListener);
            editor.removeFocusListener(editorFocusListener);
        }

        if (newEditor != null) {
            editor = (JTextComponent) newEditor.getEditorComponent();
            editor.addKeyListener(editorKeyListener);
            editor.addFocusListener(editorFocusListener);
            editor.setDocument(this);
        }
    }

    private boolean isPrintableChar(KeyEvent e) {
        char c = e.getKeyChar();
        return !Character.isISOControl(c) && !e.isActionKey() && e.getKeyCode() != KeyEvent.VK_SHIFT
                && e.getKeyCode() != KeyEvent.VK_CONTROL && e.getKeyCode() != KeyEvent.VK_ALT;
    }

    /**
     * Navigate through items with arrow keys.
     * @param direction -1 for up, +1 for down
     */
    private void navigateSelection(int direction) {
        if (allItems.isEmpty()) return;

        int currentIndex = currentMatch != null ? allItems.indexOf(currentMatch) : -1;
        int newIndex = currentIndex + direction;

        // Wrap around
        if (newIndex < 0) newIndex = allItems.size() - 1;
        if (newIndex >= allItems.size()) newIndex = 0;

        currentMatch = allItems.get(newIndex);

        // Update text to show selected item and actually select it in combo
        updating = true;
        try {
            setTextQuietly(currentMatch.toString());
            comboBox.setSelectedItem(currentMatch);
        } finally {
            updating = false;
        }

        if (!comboBox.isPopupVisible()) {
            comboBox.setPopupVisible(true);
        }
    }

    /**
     * Confirms the current selection - applies the match.
     */
    private void confirmSelection() {
        if (currentMatch != null) {
            updating = true;
            try {
                setTextQuietly(currentMatch.toString());
                comboBox.setSelectedItem(currentMatch);
            } finally {
                updating = false;
            }
        } else if (!allowCustomValues) {
            // No valid match in selection mode - beep and restore to first item
            comboBox.getToolkit().beep();
            if (!allItems.isEmpty()) {
                currentMatch = allItems.get(0);
                updating = true;
                try {
                    setTextQuietly(currentMatch.toString());
                    comboBox.setSelectedItem(currentMatch);
                } finally {
                    updating = false;
                }
            }
        }
        // In custom mode with no match, just keep what user typed

        if (editor != null) {
            editor.setCaretPosition(getLength());
        }
        comboBox.setPopupVisible(false);
    }

    /**
     * Sets text without triggering filtering logic.
     */
    private void setTextQuietly(String text) {
        updating = true;
        try {
            super.remove(0, getLength());
            super.insertString(0, text, null);
        } catch (BadLocationException e) {
            log.error("Error setting text", e);
        } finally {
            updating = false;
        }
    }

    @Override
    public void remove(int offs, int len) throws BadLocationException {
        if (updating || matching) {
            super.remove(offs, len);
            return;
        }

        super.remove(offs, len);
        findMatch();
    }

    @Override
    public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
        if (updating || matching) {
            super.insertString(offs, str, a);
            return;
        }

        log.debug("insertString: offs={}, str='{}', currentDocLength={}", offs, str, getLength());
        super.insertString(offs, str, a);
        log.debug("After insert, doc text='{}'", getText(0, getLength()));
        findMatch();
        log.debug("After findMatch, doc text='{}'", getText(0, getLength()));
    }

    /**
     * Finds matching item based on current text.
     * Updates combo selection to highlight match in dropdown, but the matching flag
     * prevents the selection change from triggering text replacement.
     */
    private void findMatch() {
        try {
            String pattern = getText(0, getLength()).toLowerCase(Locale.ROOT);
            log.debug("Finding match for pattern: '{}'", pattern);

            if (pattern.isEmpty()) {
                // Empty pattern - match first item
                currentMatch = allItems.isEmpty() ? null : allItems.getFirst();
                if (currentMatch != null) {
                    selectItemQuietly(currentMatch);
                }
                return;
            }

            // Find first item containing the pattern (case-insensitive)
            currentMatch = null;
            for (Object item : allItems) {
                if (item != null && item.toString().toLowerCase(Locale.ROOT).contains(pattern)) {
                    currentMatch = item;
                    log.debug("Found match: '{}'", currentMatch);
                    break;
                }
            }

            if (currentMatch != null) {
                // Update combo selection to highlight in dropdown
                // The matching flag prevents this from changing our text
                selectItemQuietly(currentMatch);
            } else {
                log.debug("No match found for pattern: '{}'", pattern);
            }
        } catch (BadLocationException e) {
            log.error("Error during find match", e);
        }
    }

    /**
     * Selects an item in the dropdown without triggering text updates.
     * Saves the current text and restores it after selection change.
     */
    private void selectItemQuietly(Object item) {
        matching = true;
        try {
            // Save current text before selection change
            String currentText = getText(0, getLength());

            int index = allItems.indexOf(item);
            if (index >= 0) {
                comboBox.setSelectedIndex(index);

                // Restore text immediately after setSelectedIndex
                // The combo replaces text synchronously, so we can restore right away
                super.remove(0, getLength());
                super.insertString(0, currentText, null);
                if (editor != null) {
                    editor.setCaretPosition(currentText.length());
                }
            }
        } catch (BadLocationException e) {
            log.error("Error in selectItemQuietly", e);
        } finally {
            matching = false;
        }
    }

    // Test harness
    private static void createAndShowGUI() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // Selection mode (must pick from list)
        JLabel label1 = new JLabel("Selection mode (must pick from list):");
        final JComboBox<String> combo1 = new JComboBox<>(
                new String[]{"", "Europe/Stockholm (UTC+01:00)", "Europe/London (UTC+00:00)",
                        "America/New_York (UTC-05:00)", "Asia/Tokyo (UTC+09:00)", "UTC (UTC+00:00)"});
        enable(combo1, false);

        // Custom mode (can type anything)
        JLabel label2 = new JLabel("Custom mode (can type anything):");
        final JComboBox<String> combo2 = new JComboBox<>(
                new String[]{"", "Apple", "Banana", "Cherry", "Date", "Elderberry"});
        enable(combo2, true);

        panel.add(label1);
        panel.add(combo1);
        panel.add(Box.createVerticalStrut(20));
        panel.add(label2);
        panel.add(combo2);

        final JFrame frame = new JFrame("AutoCompletion2 Test");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(AutoCompletion2::createAndShowGUI);
    }
}
