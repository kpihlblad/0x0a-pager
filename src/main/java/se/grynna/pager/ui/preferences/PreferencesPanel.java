package se.grynna.pager.ui.preferences;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.KeyFlavour;
import se.grynna.pager.ui.KeyMap;
import se.grynna.pager.ui.UiLayout;
import se.grynna.pager.ui.autocomplete.AutoCompletion2;
import se.grynna.pager.ui.login.FormGrid;
import se.grynna.pager.ui.login.KeyButton;
import se.grynna.pager.ui.login.Section;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.Theme;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Preferences panel for configuring date/time display settings.
 * Allows users to select their preferred timezone and locale for
 * displaying ZonedDateTime values throughout the application.
 *
 * Features intuitive combo boxes with live preview using SHORT date format.
 */
@Slf4j
public class PreferencesPanel extends JPanel {

    private final Config config;
    private final Consumer<Boolean> onClose; // true = saved, false = cancelled
    private static final Border BORDER_HEADER = new EmptyBorder(14, 24, 10, 24);
    private static final Border BORDER_CONTENT = new EmptyBorder(4, 24, 8, 24);
    private static final Border BORDER_FOOTER = new EmptyBorder(8, 24, 8, 24);
    private static final int LABEL_WIDTH = 96;
    // Sets the combo width; the lists themselves are wider
    private static final String COMBO_PROTOTYPE = "America/Argentina/Buenos_Aires (UTC-03:00)";

    private static final String CRUMB = "0x0a pager \u276F";
    private static final String TITLE = "preferences";
    private static final String DATE_TIME_SECTION = "Date & time";
    private static final String VIEW_SECTION = "View";
    static final String SAVE_BUTTON_TEXT = "save";
    static final String CANCEL_BUTTON_TEXT = "cancel";
    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_SAVE = "save";

    private final Font retroFont;

    // Preview time for showing format examples
    private final ZonedDateTime previewTime;

    // UI Components - using String-based combos for AutoCompletion2 compatibility
    private JComboBox<String> zoneComboBox;
    private JComboBox<String> localeComboBox;
    private ChoiceToggle<Theme> themeToggle;
    private ChoiceToggle<UiLayout> layoutToggle;
    private ChoiceToggle<Boolean> matchesToggle;
    private ChoiceToggle<Boolean> minimapToggle;
    private JComboBox<String> keyHintsComboBox;
    private JLabel previewLabel;

    // Maps for resolving display strings back to actual values
    private Map<String, ZoneId> zoneIdMap;
    private Map<String, Locale> localeMap;

    public PreferencesPanel(Config config, Consumer<Boolean> onClose) {
        this.config = config;
        this.onClose = onClose;
        this.retroFont = Fontalizer.getFont();
        this.previewTime = ZonedDateTime.now(ZoneId.systemDefault()).plusNanos(11); // Unique time for preview

        setBackground(DarculaColors.BACKGROUND);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;

        PreferencesBox box = new PreferencesBox();
        add(box, gbc);
    }

    /**
     * The preferences form, laid out like the login box: a header, ruled sections and a footer of key buttons.
     */
    class PreferencesBox extends JPanel {

        public PreferencesBox() {
            initComponents();
        }

        private void initComponents() {
            setLayout(new BorderLayout());
            setBackground(DarculaColors.BACKGROUND);
            setBorder(BorderFactory.createLineBorder(DarculaColors.G4));

            zoneComboBox = createZoneComboBox();
            localeComboBox = createLocaleComboBox();
            themeToggle = new ChoiceToggle<>(retroFont, Theme.DARK, "dark", Theme.LIGHT, "light",
                    config.getTheme());
            layoutToggle = new ChoiceToggle<>(retroFont, UiLayout.MOST, UiLayout.MOST.displayName(), UiLayout.WIN,
                    UiLayout.WIN.displayName(), config.getUiLayout());
            matchesToggle = new ChoiceToggle<>(retroFont, Boolean.FALSE, "whole events", Boolean.TRUE,
                    "first line only (⏎+N)", config.getMatchesFirstLineOnly());
            minimapToggle = new ChoiceToggle<>(retroFont, Boolean.TRUE, "show", Boolean.FALSE, "hide",
                    config.getShowMinimap());
            // Which key tradition menus and hints show first (all keys always work)
            keyHintsComboBox = new JComboBox<>(
                    Arrays.stream(KeyFlavour.values()).map(KeyFlavour::displayName).toArray(String[]::new));
            keyHintsComboBox.setSelectedIndex(KeyFlavour.fromName(config.getKeyHints()).ordinal());
            for (JComboBox<String> combo : List.of(zoneComboBox, localeComboBox, keyHintsComboBox)) {
                combo.setFont(retroFont);
                combo.setPrototypeDisplayValue(COMBO_PROTOTYPE);
            }

            previewLabel = new JLabel();
            previewLabel.setFont(retroFont);
            previewLabel.setForeground(DarculaColors.GREEN);
            updatePreview();

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setOpaque(false);
            content.setBorder(BORDER_CONTENT);
            content.add(new Section(DATE_TIME_SECTION, retroFont, FormGrid.of(LABEL_WIDTH,
                    new String[]{"timezone", "locale", "preview"}, zoneComboBox, localeComboBox, previewLabel)));
            content.add(new Section(VIEW_SECTION, retroFont, FormGrid.of(LABEL_WIDTH,
                    new String[]{"theme", "ui-layout", "matches", "minimap", "key hints"}, themeToggle, layoutToggle,
                    matchesToggle, minimapToggle, keyHintsComboBox)));

            add(createHeader(), BorderLayout.NORTH);
            add(content, BorderLayout.CENTER);
            add(createFooter(), BorderLayout.SOUTH);

            // Esc cancels and Ctrl+Enter saves from anywhere in the overlay
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_CANCEL, () -> onClose.accept(false));
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), ACTION_SAVE,
                    this::savePreferences);

            // Set initial focus to zone combo box when panel is shown
            SwingUtilities.invokeLater(() -> zoneComboBox.requestFocusInWindow());
        }

        private void bind(KeyStroke key, String name, Runnable action) {
            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    action.run();
                }
            });
        }

        /** Top strip like the login box's: a breadcrumb-style title. */
        private JPanel createHeader() {
            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            header.setOpaque(false);
            header.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, DarculaColors.G3), BORDER_HEADER));

            JLabel crumb = new JLabel(CRUMB);
            crumb.setFont(retroFont);
            crumb.setForeground(DarculaColors.G6);
            JLabel title = new JLabel(TITLE);
            title.setFont(Fontalizer.getBoldFont());
            title.setForeground(DarculaColors.FOREGROUND);

            header.add(crumb);
            header.add(title);
            return header;
        }

        /** Bottom strip like the login box's: the key buttons at the right. */
        private JPanel createFooter() {
            JButton cancelButton = new KeyButton("Esc", CANCEL_BUTTON_TEXT, retroFont, false);
            cancelButton.addActionListener(e -> onClose.accept(false));
            JButton saveButton = new KeyButton("Ctrl+Enter", SAVE_BUTTON_TEXT, retroFont, true);
            saveButton.addActionListener(e -> savePreferences());

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(cancelButton);
            buttons.add(saveButton);

            JPanel footer = new JPanel(new BorderLayout());
            footer.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
            footer.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G3), BORDER_FOOTER));
            footer.add(buttons, BorderLayout.EAST);
            return footer;
        }

        private JComboBox<String> createZoneComboBox() {
            zoneIdMap = new LinkedHashMap<>();
            var currentZone = config.getZoneId();
            String selectedDisplay = null;

            // Get all available zone IDs and sort them intelligently
            var zoneIds = ZoneId.getAvailableZoneIds();
            var sortedZones = new ArrayList<>(zoneIds);
            sortedZones.sort((a, b) -> {
                int priorityA = getZonePriority(a);
                int priorityB = getZonePriority(b);
                if (priorityA != priorityB) return priorityA - priorityB;
                return a.compareTo(b);
            });

            for (String zoneId : sortedZones) {
                try {
                    ZoneId zone = ZoneId.of(zoneId);
                    String offset = ZonedDateTime.now(zone).getOffset().getId();
                    String displayName = zoneId + " (UTC" + (offset.equals("Z") ? "+00:00" : offset) + ")";
                    zoneIdMap.put(displayName, zone);
                    if (zone.equals(currentZone)) {
                        selectedDisplay = displayName;
                    }
                } catch (Exception e) {
                    // Skip invalid zones
                }
            }

            String[] values = zoneIdMap.keySet().toArray(new String[0]);
            JComboBox<String> combo = new JComboBox<>(values);
            combo.setEditable(true);
            combo.setMaximumRowCount(15);
            combo.setSelectedItem(selectedDisplay != null ? selectedDisplay : "");

            // Enable autocomplete like FilterRow does
            AutoCompletion2.enable(combo);

            combo.addActionListener(e -> updatePreview());
            return combo;
        }

        private int getZonePriority(String zoneId) {
            // Priority groups: lower = shown first
            if (zoneId.equals("UTC") || zoneId.equals("GMT")) return 0;
            if (zoneId.equals(ZoneId.systemDefault().getId())) return 1;
            if (zoneId.startsWith("Europe/")) return 2;
            if (zoneId.startsWith("America/")) return 3;
            if (zoneId.startsWith("Asia/")) return 4;
            if (zoneId.startsWith("Australia/")) return 5;
            if (zoneId.startsWith("Africa/")) return 6;
            if (zoneId.startsWith("Pacific/")) return 7;
            return 10; // Other zones (Etc/, SystemV/, etc.)
        }

        private JComboBox<String> createLocaleComboBox() {
            localeMap = new LinkedHashMap<>();
            Locale currentLocale = config.getLocale();
            String selectedDisplay = null;

            // Get common locales sorted by display name
            Locale[] availableLocales = Locale.getAvailableLocales();
            Map<String, Locale> uniqueLocales = new TreeMap<>();

            for (Locale locale : availableLocales) {
                // Filter to locales with meaningful display names
                String displayName = locale.getDisplayName(Locale.ENGLISH);
                if (!displayName.isEmpty() && !locale.getLanguage().isEmpty()) {
                    String key = locale.toLanguageTag();
                    if (!uniqueLocales.containsKey(key)) {
                        uniqueLocales.put(key, locale);
                    }
                }
            }

            // Build display strings and sort by them
            List<Map.Entry<String, Locale>> entries = new ArrayList<>();
            for (Locale locale : uniqueLocales.values()) {
                String name = locale.getDisplayName(Locale.ENGLISH);
                String displayName = name + " [" + locale.toLanguageTag() + "]";
                entries.add(Map.entry(displayName, locale));
            }
            entries.sort(Comparator.comparing(Map.Entry::getKey));

            for (Map.Entry<String, Locale> entry : entries) {
                localeMap.put(entry.getKey(), entry.getValue());
                if (entry.getValue().equals(currentLocale)
                        || entry.getValue().toLanguageTag().equals(currentLocale.toLanguageTag())) {
                    selectedDisplay = entry.getKey();
                }
            }

            String[] values = localeMap.keySet().toArray(new String[0]);
            JComboBox<String> combo = new JComboBox<>(values);
            combo.setEditable(true);
            combo.setMaximumRowCount(15);
            combo.setSelectedItem(selectedDisplay != null ? selectedDisplay : "");

            // Enable autocomplete like FilterRow does
            AutoCompletion2.enable(combo);

            combo.addActionListener(e -> updatePreview());
            return combo;
        }

        private void updatePreview() {
            ZoneId zoneId = getSelectedZone();
            Locale locale = getSelectedLocale();

            if (zoneId != null && locale != null) {
                try {
                    ZonedDateTime converted = previewTime.withZoneSameInstant(zoneId);
                    //                    DateTimeFormatter formatter = DateTimeFormatter
                    //                        .ofLocalizedDateTime(FormatStyle.SHORT)
                    //                        .withLocale(locale);

                    DateTimeFormatter formatter = DTUtil.createTimeFormatterWithMillis(locale);

                    String formatted = converted.format(formatter);
                    previewLabel.setText(formatted);
                } catch (Exception e) {
                    previewLabel.setText("(invalid selection)");
                }
            } else {
                previewLabel.setText("(select timezone and locale)");
            }
        }

        private ZoneId getSelectedZone() {
            Object selected = zoneComboBox.getSelectedItem();
            if (selected instanceof String text) {
                // First try exact match in our map
                if (zoneIdMap.containsKey(text)) {
                    return zoneIdMap.get(text);
                }
                // Try to find partial match
                for (Map.Entry<String, ZoneId> entry : zoneIdMap.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(text)) {
                        return entry.getValue();
                    }
                }
                // Try direct zone ID parsing (user might type just "Europe/Stockholm")
                try {
                    return ZoneId.of(text);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        private Locale getSelectedLocale() {
            Object selected = localeComboBox.getSelectedItem();
            if (selected instanceof String text) {
                // First try exact match in our map
                if (localeMap.containsKey(text)) {
                    return localeMap.get(text);
                }
                // Try to find partial match
                for (Map.Entry<String, Locale> entry : localeMap.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(text)) {
                        return entry.getValue();
                    }
                }
                // Try direct locale parsing (user might type just "sv-SE")
                try {
                    Locale parsed = Locale.forLanguageTag(text);
                    if (!parsed.getLanguage().isEmpty()) {
                        return parsed;
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        private void savePreferences() {
            ZoneId zoneId = getSelectedZone();
            Locale locale = getSelectedLocale();

            if (zoneId == null || locale == null) {
                JOptionPane.showMessageDialog(this, "Please select valid timezone and locale", "Invalid Selection",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            config.setZoneId(zoneId);
            config.setLocale(locale);
            config.setTheme(themeToggle.selected());
            config.setUiLayout(layoutToggle.selected());
            config.setMatchesFirstLineOnly(matchesToggle.selected());
            config.setShowMinimap(minimapToggle.selected());
            KeyFlavour keyHints = KeyFlavour.values()[Math.max(0, keyHintsComboBox.getSelectedIndex())];
            config.setKeyHints(keyHints.name());
            KeyMap.setPreferredFlavour(keyHints);

            try {
                config.flush();
                // Refresh the centralized formatter with new locale
                DateTimeFormatterManager.getInstance().refresh();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Failed to save preferences: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            onClose.accept(true);
        }
    }

    /**
     * A two-option horizontal segmented toggle for preferences that have only two choices (theme, ui-layout, matches,
     * minimap). Both options sit side by side in a shared rounded box; the selected segment is filled in {@code BLUE}
     * with bold text.
     */
    static final class ChoiceToggle<T> extends JPanel {

        private static final int BOX_ARC = 8;
        private static final int PAD_X = 12;
        private static final int PAD_Y = 4;

        private final T firstValue;
        private final T secondValue;
        private final SegmentButton firstButton;
        private final SegmentButton secondButton;

        ChoiceToggle(Font font, T firstValue, String firstLabel, T secondValue, String secondLabel, T selected) {
            super(new GridLayout(1, 2, 1, 0));
            this.firstValue = firstValue;
            this.secondValue = secondValue;
            setOpaque(false);

            firstButton = new SegmentButton(firstLabel, font, true);
            secondButton = new SegmentButton(secondLabel, font, false);
            ButtonGroup group = new ButtonGroup();
            group.add(firstButton);
            group.add(secondButton);
            if (Objects.equals(selected, secondValue)) {
                secondButton.setSelected(true);
            } else {
                firstButton.setSelected(true);
            }

            ActionListener repaintBoth = e -> repaint();
            firstButton.addActionListener(repaintBoth);
            secondButton.addActionListener(repaintBoth);
            add(firstButton);
            add(secondButton);
        }

        T selected() {
            return secondButton.isSelected() ? secondValue : firstValue;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                Shape box = new RoundRectangle2D.Float(0, 0, w, h, BOX_ARC, BOX_ARC);
                g2.setColor(DarculaColors.BACKGROUND_OUTSIDE);
                g2.fill(box);

                Shape clip = g2.getClip();
                g2.clip(box);
                paintSegmentFill(g2, firstButton);
                paintSegmentFill(g2, secondButton);
                g2.setColor(DarculaColors.G4);
                int split = secondButton.getX() - 1;
                g2.drawLine(split, 1, split, h - 2);
                g2.setClip(clip);

                boolean focused = firstButton.isFocusOwner() || secondButton.isFocusOwner();
                g2.setColor(focused ? DarculaColors.BORDER_HIGHLIGHT : DarculaColors.G4);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, BOX_ARC, BOX_ARC));
            } finally {
                g2.dispose();
            }
        }

        private void paintSegmentFill(Graphics2D g2, SegmentButton button) {
            ButtonModel model = button.getModel();
            if (button.isSelected()) {
                g2.setColor(DarculaColors.BLUE);
                g2.fill(button.getBounds());
            } else if (model.isRollover() || model.isPressed()) {
                g2.setColor(DarculaColors.BACKGROUND_PLUS);
                g2.fill(button.getBounds());
            }
        }

        private final class SegmentButton extends JToggleButton {
            private final Font plainFont;
            private final Font boldFont;
            private final boolean first;

            SegmentButton(String text, Font font, boolean first) {
                super(text);
                this.plainFont = font;
                this.boldFont = font.deriveFont(Font.BOLD);
                this.first = first;
                setFont(plainFont);
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setOpaque(false);
                setRolloverEnabled(true);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        ChoiceToggle.this.repaint();
                    }

                    @Override
                    public void focusLost(FocusEvent e) {
                        ChoiceToggle.this.repaint();
                    }
                });
                bindArrow(KeyEvent.VK_LEFT, () -> selectSibling(firstButton));
                bindArrow(KeyEvent.VK_RIGHT, () -> selectSibling(secondButton));
            }

            private void bindArrow(int keyCode, Runnable action) {
                String name = "segment-" + keyCode + "-" + first;
                getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
                getActionMap().put(name, new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        action.run();
                    }
                });
            }

            private void selectSibling(SegmentButton target) {
                target.setSelected(true);
                target.requestFocusInWindow();
                ChoiceToggle.this.repaint();
            }

            @Override
            public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(boldFont);
                return new Dimension(fm.stringWidth(getText()) + 2 * PAD_X, fm.getHeight() + 2 * PAD_Y);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    boolean selected = isSelected();
                    Font f = selected ? boldFont : plainFont;
                    FontMetrics fm = g2.getFontMetrics(f);
                    g2.setFont(f);
                    boolean hot = getModel().isRollover() || getModel().isPressed();
                    g2.setColor(selected || hot ? DarculaColors.FOREGROUND : DarculaColors.G7);
                    int x = (getWidth() - fm.stringWidth(getText())) / 2;
                    int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(getText(), x, y);
                } finally {
                    g2.dispose();
                }
            }
        }
    }
}
