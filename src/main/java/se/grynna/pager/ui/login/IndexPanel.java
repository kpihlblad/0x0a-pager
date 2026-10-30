package se.grynna.pager.ui.login;

import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import raven.toast.Notifications;
import se.grynna.pager.db.ScopeTerms;
import se.grynna.pager.db.SearchFilterManager.FieldSummaryUpdateListener;
import se.grynna.pager.db.SearchFilterManager.IndexUpdateListener;
import se.grynna.pager.exceptions.ExceptionMessageFormatter;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.splunk.FieldSampling;
import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.IndexSelection;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchProgress;
import se.grynna.pager.ui.BlockCaret;
import se.grynna.pager.ui.BookmarkNotices;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.LogViewer;
import se.grynna.pager.ui.sprattel.ProgressButton;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Clock;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;

import static java.util.concurrent.CompletableFuture.runAsync;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Index selection and search configuration panel.
 *
 * <h2>Lazy Initialization</h2>
 * Can be created before SearchManager is initialized (before login).
 * Shows loading state until onSearchManagerInitialized() is called,
 * then loads indexes and field data from DatabaseManager.
 *
 * <h2>Database Dependency</h2>
 * Requires DatabaseManager (created during SearchManager.initialize()).
 * All database operations are null-safe until initialization completes.
 */
@Slf4j
public class IndexPanel extends JPanel {

    private static final Border BORDER_HEADER = new EmptyBorder(14, 24, 10, 24);
    private static final Border BORDER_CONTENT = new EmptyBorder(4, 24, 8, 24);
    private static final Border BORDER_FOOTER = new EmptyBorder(8, 24, 8, 24);
    private static final Border BORDER_QUERY_TEXT = new EmptyBorder(8, 4, 8, 8);

    private static final String GREEN_DOT = "● ";
    private static final String CRUMB = "0x0a pager \u276F";
    private static final String TITLE = "scope";
    private static final String LOGOUT_BUTTON_TEXT = "logout";
    private static final String STEP_1_LABEL = "Index";
    private static final String STEP_2_LABEL = "Field filters";
    private static final String STEP_2_META_OPTIONAL = "optional";
    private static final String META_SEPARATOR = " \u00B7 ";
    private static final String NARROW_TERM_KEY = "+ narrow";
    private static final String EXCLUDE_TERM_KEY = "\u2212 exclude";
    private static final String STEP_3_LABEL = "Query";
    private static final String PROMPT = "\u276F";
    private static final String REFRESHING_INDEXES_LABEL = "Refreshing indexes...";
    private static final String ADVANCED_MODE_BUTTON_TEXT = "advanced";
    private static final String CANCEL_BUTTON_TEXT = "cancel";
    private static final String APPLY_BUTTON_TEXT = "apply";
    private static final String LOADING_FIELDS_LABEL = "Loading field summaries...";
    private static final String NO_FIELDS_LABEL = "No fields available for this index.";

    private static final String REFRESH_ICON_PATH = "/Refresh_icon.svg";
    private static final String REFRESH_INDEXES_TOOLTIP = "Refresh indexes";
    private static final String REFRESH_FIELDS_TOOLTIP = "Look deeper: sample 10\u00D7 more events, further back";
    private static final String SWEEP_ICON_PATH = "/sweep.svg";
    private static final String CLEAR_FILTERS_TOOLTIP = "Clear all field filters";
    private static final String FIND_FIELD_PLACEHOLDER = "find field";
    private static final String FIND_FIELD_TOOLTIP = "Find a field by name (Ctrl+F)";
    private static final String PINNED_HEADING = "set";
    private static final String UNPINNED_HEADING = "fields";
    private static final int FIND_FIELD_COLUMNS = 16;
    // The label column fits the longest label within these bounds; a longer one is elided from the left.
    // Labels are right-aligned, so a wide column does not move a short label away from its value.
    private static final int LABEL_WIDTH_MIN = 120;
    private static final int LABEL_WIDTH_MAX = 360;

    private static final Dimension FILTER_SCROLL_PANE_SIZE = new Dimension(0, 200);
    private static final int GENERATED_QUERY_AREA_ROWS = 3;
    private static final float SMALL_FONT_SIZE = 11f;

    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_REFRESH = "refresh";
    private static final String ACTION_APPLY = "apply";
    private static final String ACTION_FIND = "find";

    private final Config config;
    private final SearchManager searchManager;

    // UI State
    private boolean advancedMode = false;
    // EDT-owned. currentFilters is the filter state of currentIndex, including values
    // for fields that have no row yet; rows are written back into it before a rebuild.
    private String currentIndex = null;
    private Map<String, String> currentFilters = new HashMap<>();
    // The text terms (narrow / exclude) saved for currentIndex. They are part of the scope
    // that Apply builds, so applying field filters does not drop them.
    private ScopeTerms currentTerms = ScopeTerms.EMPTY;

    private BiConsumer<String, Notifications.Type> notifier = IndexPanel::showToast;

    // Asked before Apply drops bookmarks that fall outside the new scope; the status bar is hidden
    // behind this overlay, so the question is a dialog (replaced in tests).
    @Setter
    private Confirmer confirmer = Confirmer.dialog();
    @Setter
    private BookmarkScopeGuard bookmarkGuard;

    // UI Components
    private IndexPicker indexPicker;
    private Font font;
    private Font boldFont;
    private Font smallFont;

    // Clean refresh indicators using SpinningSVGButton
    private ProgressButton indexRefreshButton;
    private ProgressButton fieldRefreshButton;

    private JLabel indexRefreshLabel;

    // Ruled sections (title, hairline, tools) that hold the three steps
    private Section indexSection;
    private Section filtersSection;
    private Section querySection;

    // Filter components
    private JPanel filterContainer;
    private JScrollPane filterScrollPane;
    private final List<FilterRow> filterRows = new ArrayList<>();
    private final List<ScopeTermRow> termRows = new ArrayList<>();
    private JTextField findField;
    // Fields that had a saved value when the index was entered; listed first under "set" for the visit,
    // so a row does not jump away while its value is being edited
    private Set<String> pinnedFields = Set.of();

    // Compiled patterns for highlighting important fields in the filter selector
    private List<java.util.regex.Pattern> highlightPatterns = List.of();

    // Query components. queryHost shows either the generated query or the manual field.
    private JTextField manualQueryField;
    private JTextArea generatedQueryArea;
    private JScrollPane generatedQueryScroll;
    private JPanel queryHost;
    private JToggleButton advancedToggle;

    // Action components
    private JButton applyButton;
    private JButton cancelButton;
    private JButton clearFiltersButton;

    // Callback functions for navigation
    @Setter
    private Runnable onLogout;

    @Setter
    private java.util.function.Consumer<SearchManager> onLoginComplete;

    @Setter
    private Runnable onCancel;

    public IndexPanel(SearchManager searchManager, LogViewer.CharacterSize charSize, Clock clock) {
        log.info("Creating IndexPanel");
        this.searchManager = searchManager;

        searchManager.setIndexProgressCallback(this::onIndexProgress);
        searchManager.setFieldProgressCallback(this::onFieldProgress);
        searchManager.setFieldSummaryUpdateListener(new FieldSummaryUpdateListener() {
            @Override
            public void onFieldSummariesUpdated(String index, SortedSet<FieldSummary> fieldSummaries,
                    boolean fromCache) {
                IndexPanel.this.onFieldSummariesUpdated(index, fieldSummaries, fromCache);
            }

            @Override
            public void onFieldSummaryRefreshFailed(String index, Throwable error) {
                IndexPanel.this.onFieldSummaryRefreshFailed(index, error);
            }
        });
        searchManager.setIndexUpdateListener(new IndexUpdateListener() {
            @Override
            public void onIndexesUpdated(SortedSet<String> indexes, boolean fromCache) {
                indexUpdated(indexes, fromCache);
            }

            @Override
            public void onIndexRefreshFailed(Throwable error) {
                IndexPanel.this.onIndexRefreshFailed(error);
            }
        });

        // Load field highlight patterns from configuration
        String patterns = searchManager.getFieldHighlightPatterns();
        this.highlightPatterns = FilterRow.compileGlobPatterns(patterns);
        log.debug("Loaded {} field highlight patterns: {}", highlightPatterns.size(), patterns);

        this.config = new Config();
        initComponents();

        runAsync(this::loadIndexesAndInitialize).exceptionally(e -> {
            handleAsyncException(e);
            return null;
        });

        log.debug("Created IndexPanel");
    }

    private void handleAsyncException(Throwable e) {
        SwingUtilities.invokeLater(() -> {
            String errorMessage = ExceptionMessageFormatter.formatException(e);
            log.error("Error loading indexes: {}", errorMessage, e);
            showNotification("Error loading indexes: " + errorMessage, Notifications.Type.ERROR);
            handleCancel();
        });
    }

    /**
     * Handle index refresh progress updates.
     * Updates the index refresh button with progress.
     */
    private void onIndexProgress(SearchProgress progress) {
        log.debug("onIndexProgress: progress={}", progress);
        SwingUtilities.invokeLater(() -> onProgress(progress, indexRefreshButton));
    }

    /**
     * Handle field refresh progress updates.
     * Updates the field refresh button with progress.
     */
    private void onFieldProgress(SearchProgress progress) {
        log.debug("onFieldProgress: progress={}", progress);
        SwingUtilities.invokeLater(() -> onProgress(progress, fieldRefreshButton));
    }

    private static void onProgress(SearchProgress progress, ProgressButton progressButton) {
        progressButton.setRefreshing(!progress.isDone());
        if (progress.indeterminate()) {
            progressButton.setIndeterminate(true);
        } else {
            progressButton.setProgress(progress.doneProgress());
        }
    }

    /**
     * Load indexes from SearchFilterManager and initialize the UI.
     * If no cached indexes exist, shows "Loading..." and disables selector.
     * If cached indexes exist, shows them immediately and refreshes in background.
     */
    @SneakyThrows
    private void loadIndexesAndInitialize() {
        log.info("loadIndexesAndInitialize: Loading indexes");

        var indexes = searchManager.loadIndexes();
        var lastIndex = searchManager.getLastIndex();

        log.info("loadIndexesAndInitialize: Found {} cached indexes, last {}", indexes.size(), lastIndex);
        SwingUtilities.invokeLater(() -> {
            updateIndexPicker(indexes);
            indexPicker.setEnabled(true);

            if (isNotBlank(lastIndex)) {
                log.info("Restoring last selected index: {}", lastIndex);
                indexPicker.setSelectionKey(lastIndex);
            }
        });
    }

    private void indexUpdated(SortedSet<String> indexes, boolean fromCache) {
        SwingUtilities.invokeLater(() -> {
            log.debug("Received index update: {} indexes (fromCache: {})", indexes.size(), fromCache);

            // The picker keeps its selection, also for indexes the new list no longer has
            updateIndexPicker(indexes);
            indexPicker.setEnabled(true);

            // Stop spinner when fresh data arrives from Splunk
            if (!fromCache) {
                indexRefreshButton.setRefreshing(false);
            }
        });
    }

    /**
     * The current index refresh failed: stop its progress and report the error.
     * The refresh button stays usable for a retry.
     */
    private void onIndexRefreshFailed(Throwable error) {
        SwingUtilities.invokeLater(() -> {
            log.warn("Index refresh failed: {}", error.toString());
            indexRefreshButton.setRefreshing(false);
            indexPicker.setEnabled(true);
            showNotification("Failed to refresh indexes: " + ExceptionMessageFormatter.formatException(error),
                    Notifications.Type.ERROR);
        });
    }

    private void initComponents() {
        log.info("Creating initComponents");
        setLayout(new BorderLayout());
        setBackground(DarculaColors.BACKGROUND);

        font = Fontalizer.getFont();
        boldFont = Fontalizer.getBoldFont();
        smallFont = font.deriveFont(SMALL_FONT_SIZE);

        indexSection = new Section(STEP_1_LABEL, font, createIndexSelectPanel());
        filtersSection = new Section(STEP_2_LABEL, font, createFilterScrollPane(), createFilterTools());
        filtersSection.setMeta(STEP_2_META_OPTIONAL);
        querySection = new Section(STEP_3_LABEL, font, createQueryBox(), createAdvancedToggle());

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(DarculaColors.BACKGROUND);
        content.setBorder(BORDER_CONTENT);
        content.add(indexSection, BorderLayout.NORTH);
        content.add(filtersSection, BorderLayout.CENTER);
        content.add(querySection, BorderLayout.SOUTH);

        add(createHeaderPanel(), BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(createFooterPanel(), BorderLayout.SOUTH);

        setupKeyBindings();
        revalidate();
        log.debug("Created initComponents");
    }

    /** Top strip: a breadcrumb-style title on the left, the connection and logout on the right. */
    private JPanel createHeaderPanel() {
        log.info("Creating HeaderPanel");
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(DarculaColors.BACKGROUND);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, DarculaColors.G3), BORDER_HEADER));

        JLabel crumb = new JLabel(CRUMB);
        crumb.setFont(font);
        crumb.setForeground(DarculaColors.G6);
        JLabel title = new JLabel(TITLE);
        title.setFont(boldFont);
        title.setForeground(DarculaColors.FOREGROUND);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(crumb);
        left.add(title);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);
        right.add(createConnectionLabel());
        right.add(createLogoutButton());

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        log.debug("Created HeaderPanel");
        return header;
    }

    private JLabel createConnectionLabel() {
        JLabel connection = new JLabel(GREEN_DOT + config.getSplunkHost());
        connection.setFont(font);
        connection.setForeground(DarculaColors.GREEN);
        return connection;
    }

    private JButton createLogoutButton() {
        JButton logout = flatButton(new JButton(LOGOUT_BUTTON_TEXT));
        logout.addActionListener(e -> handleLogout());
        return logout;
    }

    /** FlatLaf's borderless tool-bar style: dim text only, a background on hover or when selected. */
    private <T extends AbstractButton> T flatButton(T button) {
        button.setFont(smallFont);
        button.setForeground(DarculaColors.G7);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusPainted(false);
        return button;
    }

    private void createIndexPicker() {
        log.info("Creating IndexPicker");
        indexPicker = new IndexPicker();
        indexPicker.setOnChange(this::onIndexSelected);
        log.debug("Created IndexPicker");
    }

    private void createIndexRefreshComponents() {
        log.info("Creating IndexRefreshComponents");
        indexRefreshButton = new ProgressButton(REFRESH_ICON_PATH, REFRESH_INDEXES_TOOLTIP);
        indexRefreshButton.addActionListener(e -> refreshIndexes());

        indexRefreshLabel = new JLabel(REFRESHING_INDEXES_LABEL);
        indexRefreshLabel.setFont(smallFont);
        indexRefreshLabel.setForeground(DarculaColors.G7);
        indexRefreshLabel.setVisible(false);
        log.debug("Created IndexRefreshComponents");
    }

    private JPanel createIndexSelectPanel() {
        log.info("Creating IndexSelectPanel");
        JPanel indexSelectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        indexSelectPanel.setOpaque(false);

        createIndexPicker();
        createIndexRefreshComponents();

        indexSelectPanel.add(indexPicker);
        indexSelectPanel.add(indexRefreshButton);
        indexSelectPanel.add(indexRefreshLabel);
        log.debug("Created IndexSelectPanel");
        return indexSelectPanel;
    }

    private JComponent[] createFilterTools() {
        log.info("Creating FilterTools");
        findField = new JTextField(FIND_FIELD_COLUMNS);
        findField.setFont(smallFont);
        findField.putClientProperty("JTextField.placeholderText", FIND_FIELD_PLACEHOLDER);
        findField.setToolTipText(FIND_FIELD_TOOLTIP);
        findField.putClientProperty("JTextField.showClearButton", true);
        findField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyFind();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyFind();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyFind();
            }
        });
        // Esc clears the find text first; with nothing to clear it cancels like elsewhere on the panel
        findField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                ACTION_CANCEL);
        findField.getActionMap().put(ACTION_CANCEL, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (findField.getText().isEmpty()) {
                    handleCancel();
                } else {
                    findField.setText("");
                }
            }
        });

        clearFiltersButton = new ProgressButton(SWEEP_ICON_PATH, CLEAR_FILTERS_TOOLTIP);
        clearFiltersButton.addActionListener(e -> clearAllFilters());

        fieldRefreshButton = new ProgressButton(REFRESH_ICON_PATH, REFRESH_FIELDS_TOOLTIP);
        fieldRefreshButton.addActionListener(e -> refreshFields(currentIndex, true, "UserClicked"));
        log.debug("Created FilterTools");
        return new JComponent[]{findField, clearFiltersButton, fieldRefreshButton};
    }

    private JScrollPane createFilterScrollPane() {
        log.info("Creating FilterContainerAndScrollPane");
        filterContainer = new JPanel();
        filterContainer.setLayout(new BoxLayout(filterContainer, BoxLayout.Y_AXIS));
        filterContainer.setBackground(DarculaColors.BACKGROUND);

        filterScrollPane = new JScrollPane(filterContainer);
        filterScrollPane.setBackground(DarculaColors.BACKGROUND);
        filterScrollPane.getViewport().setBackground(DarculaColors.BACKGROUND);
        filterScrollPane.setBorder(BorderFactory.createEmptyBorder());
        filterScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        filterScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        filterScrollPane.setPreferredSize(FILTER_SCROLL_PANE_SIZE);
        filterScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        log.debug("Created FilterContainerAndScrollPane");
        return filterScrollPane;
    }

    private void createQueryArea() {
        log.info("Creating QueryArea");
        generatedQueryArea = new JTextArea(GENERATED_QUERY_AREA_ROWS, 0);
        generatedQueryArea.setFont(font);
        generatedQueryArea.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        generatedQueryArea.setForeground(DarculaColors.FOREGROUND);
        generatedQueryArea.setBorder(BORDER_QUERY_TEXT);
        generatedQueryArea.setEditable(false);
        generatedQueryArea.setLineWrap(true);
        generatedQueryArea.setWrapStyleWord(true);

        generatedQueryScroll = new JScrollPane(generatedQueryArea);
        generatedQueryScroll.setBorder(BorderFactory.createEmptyBorder());
        generatedQueryScroll.getViewport().setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        log.debug("Created QueryArea");
    }

    private void createManualQueryField() {
        log.info("Creating ManualQueryField");
        manualQueryField = new JTextField();
        manualQueryField.setFont(font);
        manualQueryField.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        manualQueryField.setForeground(DarculaColors.FOREGROUND);
        manualQueryField.setBorder(BORDER_QUERY_TEXT);
        manualQueryField.setCaret(new BlockCaret(DarculaColors.ORANGE));
        log.debug("Created ManualQueryField");
    }

    /** The query in a dark box behind a prompt sign, like a shell line. */
    private JPanel createQueryBox() {
        log.info("Creating QueryBox");
        createQueryArea();
        createManualQueryField();

        JLabel prompt = new JLabel(PROMPT);
        prompt.setFont(boldFont);
        prompt.setForeground(DarculaColors.ORANGE);
        prompt.setVerticalAlignment(SwingConstants.TOP);
        prompt.setBorder(new EmptyBorder(8, 10, 8, 2));

        queryHost = new JPanel(new BorderLayout());
        queryHost.setOpaque(false);
        queryHost.add(generatedQueryScroll, BorderLayout.CENTER);

        JPanel box = new JPanel(new BorderLayout());
        box.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        box.setBorder(BorderFactory.createLineBorder(DarculaColors.G3));
        box.add(prompt, BorderLayout.WEST);
        box.add(queryHost, BorderLayout.CENTER);
        log.debug("Created QueryBox");
        return box;
    }

    private JToggleButton createAdvancedToggle() {
        advancedToggle = flatButton(new JToggleButton(ADVANCED_MODE_BUTTON_TEXT));
        advancedToggle.addActionListener(e -> toggleAdvancedMode());
        return advancedToggle;
    }

    /** Bottom strip: keyboard hints on the left, the Cancel and Apply buttons on the right. */
    private JPanel createFooterPanel() {
        log.info("Creating FooterPanel");
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G3), BORDER_FOOTER));

        // Esc and Ctrl+Enter are shown on the buttons themselves
        KeyHintBar hints = new KeyHintBar(font, new KeyHintBar.Hint("F5", "refresh"));

        cancelButton = new KeyButton("Esc", CANCEL_BUTTON_TEXT, font, false);
        cancelButton.addActionListener(e -> handleCancel());

        applyButton = new KeyButton("Ctrl+Enter", APPLY_BUTTON_TEXT, font, true);
        applyButton.addActionListener(e -> handleApply());
        applyButton.setEnabled(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(applyButton);

        // Centred vertically against the taller key buttons
        JPanel hintsHost = new JPanel(new GridBagLayout());
        hintsHost.setOpaque(false);
        hintsHost.add(hints);

        footer.add(hintsHost, BorderLayout.WEST);
        footer.add(buttons, BorderLayout.EAST);
        log.debug("Created FooterPanel");
        return footer;
    }

    private void refreshIndexes() {
        log.info("refreshIndexes: User clicked refresh button");
        try {
            searchManager.refreshIndexes();
        } catch (Exception e) {
            log.error("refreshIndexes: Failed to refresh indexes", e);
            showNotification("Failed to refresh indexes: " + ExceptionMessageFormatter.formatException(e),
                    Notifications.Type.ERROR);
        }
    }

    private void refreshFields(String selectedIndex, boolean forceRefresh, String reason) {
        log.info("refreshFields: index={}, force={}, reason={}", selectedIndex, forceRefresh, reason);
        if (isBlank(selectedIndex)) {
            log.debug("refreshFields: Blank index, ignoring");
            showNotification("Select an index", Notifications.Type.INFO);
            return;
        }

        // Show loading placeholder only if no fields exist yet
        if (filterRows.isEmpty()) {
            clearFilterPanel();
            JLabel loadingLabel = new JLabel(LOADING_FIELDS_LABEL);
            loadingLabel.setFont(font);
            loadingLabel.setForeground(DarculaColors.G7);
            filterContainer.add(loadingLabel);
            filterContainer.revalidate();
            filterContainer.repaint();
        }

        // Load field summaries - returns cached data immediately, triggers background refresh if stale
        // The onFieldSummariesUpdated listener will be called when fresh data arrives
        try {
            // A forced refresh is the user asking for more: sample deeper than last time
            SortedSet<FieldSummary> summaries = forceRefresh
                    ? searchManager.deepenFieldSummaries(selectedIndex)
                    : searchManager.loadFieldSummaries(selectedIndex, false);
            updateFiltersMeta();
            onFieldSummariesUpdated(selectedIndex, summaries, true);
        } catch (RuntimeException e) {
            log.error("refreshFields: Failed to load field summaries for index '{}'", selectedIndex, e);
            showNotification("Failed to load field summaries: " + ExceptionMessageFormatter.formatException(e),
                    Notifications.Type.ERROR);
        }
    }

    /**
     * Handle field summary updates - called directly from refreshFields with cached data,
     * and as a listener callback when fresh data arrives from Splunk background refresh.
     * Only updates UI if the index is still the current index when the update runs on the
     * EDT; a reply for an index the user has left is dropped.
     */
    public void onFieldSummariesUpdated(String index, SortedSet<FieldSummary> fieldSummaries, boolean fromCache) {
        SwingUtilities.invokeLater(() -> {
            if (!index.equals(currentIndex)) {
                log.debug("Dropping field update for '{}': current index is '{}'", index, currentIndex);
                return;
            }
            log.debug("Received field update for '{}': {} fields (fromCache: {})", index, fieldSummaries.size(),
                    fromCache);
            updateFilterPanel(fieldSummaries);

            // Stop field refresh spinner when fresh data arrives
            if (!fromCache) {
                fieldRefreshButton.setRefreshing(false);
            }
        });
    }

    /**
     * The current field-summary refresh for {@code index} failed: stop its progress and
     * report the error. The field refresh button stays usable for a retry.
     */
    private void onFieldSummaryRefreshFailed(String index, Throwable error) {
        SwingUtilities.invokeLater(() -> {
            if (!index.equals(currentIndex)) {
                log.debug("Dropping field refresh failure for '{}': current index is '{}'", index, currentIndex);
                return;
            }
            log.warn("Field summary refresh failed for '{}': {}", index, error.toString());
            fieldRefreshButton.setRefreshing(false);
            if (filterRows.isEmpty()) {
                updateFilterPanel(Collections.emptySortedSet());
            }
            showNotification("Failed to load field summaries: " + ExceptionMessageFormatter.formatException(error),
                    Notifications.Type.ERROR);
        });
    }

    /** The picker committed a new selection key (blank for none). */
    private void onIndexSelected(String key) {
        String selectedIndex = isBlank(key) ? null : key;

        if (currentIndex == null && selectedIndex == null) {
            log.debug("onIndexSelected: Both current and selected index are null, ignoring");
            return;
        }

        if (currentIndex != null && currentIndex.equals(selectedIndex)) {
            log.debug("onIndexSelected: Same index, ignoring");
            return;
        }
        log.info("onIndexSelected: Changed from '{}' to '{}'", currentIndex, selectedIndex);

        // Save the outgoing index's filters and scope terms, snapshotted here on the EDT
        if (isNotBlank(currentIndex)) {
            saveFilters(currentIndex, snapshotFilters());
            saveScopeTerms(currentIndex, currentTerms);
        }

        currentIndex = selectedIndex;
        currentFilters = new HashMap<>();
        currentTerms = ScopeTerms.EMPTY;
        // The rows belong to the outgoing index
        clearFilterPanel();

        if (isBlank(selectedIndex)) {
            log.debug("onIndexSelected: Blank/placeholder, clearing panel");
            updateGeneratedQuery();
            return;
        }

        currentFilters = loadPersistedFilters(selectedIndex);
        pinnedFields = Set.copyOf(currentFilters.keySet());
        currentTerms = loadPersistedTerms(selectedIndex);
        findField.setText("");
        renderTermRows();

        // Store last selected index to database
        var idx = selectedIndex;
        runAsync(() -> searchManager.setLastIndex(idx)).exceptionally(error -> {
            log.error("onIndexSelected: Failed to store last index '{}'", idx, error);
            return null;
        });

        refreshFields(selectedIndex, false, "onIndexSelected");
        updateGeneratedQuery();
        log.debug("onIndexSelected: Completed for '{}'", selectedIndex);
    }

    /**
     * Snapshot of the current index's filter state: the row values plus remembered
     * values for fields without a row.
     */
    private Map<String, String> snapshotFilters() {
        captureRowValues();
        return Map.copyOf(currentFilters);
    }

    /**
     * Write the values the user has set in the filter rows back into {@link #currentFilters}.
     */
    private void captureRowValues() {
        for (FilterRow row : filterRows) {
            // A row stands for all its real fields: its value is kept under the primary name only
            row.getAliases().forEach(currentFilters::remove);
            String value = row.getFilterValue();
            if (isNotBlank(value)) {
                currentFilters.put(row.getFieldName(), value);
            } else {
                currentFilters.remove(row.getFieldName());
            }
        }
    }

    /**
     * Persist a filter snapshot for {@code index} in the background. Snapshots are written
     * in order, and a pending snapshot is visible to {@link #loadPersistedFilters} at once.
     */
    // The future derived from whenComplete is dropped on purpose: the handler only logs and
    // hands off to the EDT, so it cannot complete exceptionally and there is nothing to cancel.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void saveFilters(String index, Map<String, String> filters) {
        log.info("saveFilters: {} filters for index='{}'", filters.size(), index);
        searchManager.storeFieldFilters(index, filters).whenComplete((ignored, e) -> {
            if (e != null) {
                log.error("saveFilters: Failed for index '{}'", index, e);
                SwingUtilities.invokeLater(() -> showNotification(
                        "Failed to save cached field filters: " + ExceptionMessageFormatter.formatException(e),
                        Notifications.Type.ERROR));
            }
        });
    }

    /**
     * Load the persisted filters for an index being entered.
     */
    private Map<String, String> loadPersistedFilters(String index) {
        try {
            return new HashMap<>(searchManager.getFieldFilters(index));
        } catch (Exception e) {
            log.error("loadPersistedFilters: Failed for index '{}'", index, e);
            showNotification("Failed to load cached field filters: " + ExceptionMessageFormatter.formatException(e),
                    Notifications.Type.ERROR);
            return new HashMap<>();
        }
    }

    /**
     * Persist the narrowing and excluded terms for {@code index} in the background, like
     * {@link #saveFilters}.
     */
    // Same reasoning as saveFilters: the handler only logs and hands off to the EDT.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void saveScopeTerms(String index, ScopeTerms terms) {
        log.info("saveScopeTerms: {} narrow, {} exclude for index='{}'", terms.narrow().size(),
                terms.exclude().size(), index);
        searchManager.storeScopeTerms(index, terms).whenComplete((ignored, e) -> {
            if (e != null) {
                log.error("saveScopeTerms: Failed for index '{}'", index, e);
                SwingUtilities.invokeLater(() -> showNotification(
                        "Failed to save scope terms: " + ExceptionMessageFormatter.formatException(e),
                        Notifications.Type.ERROR));
            }
        });
    }

    /**
     * Lists the saved narrowing and excluded terms above the field rows, with the same
     * remove control. Always rebuilt from {@link #currentTerms}, so edits survive a
     * refresh of the field rows.
     */
    private void renderTermRows() {
        termRows.stream().filter(row -> row.getParent() == filterContainer).forEach(filterContainer::remove);
        termRows.clear();
        for (String term : currentTerms.narrow()) {
            termRows.add(new ScopeTermRow(NARROW_TERM_KEY, term, () -> dropScopeTerm(true, term)));
        }
        for (String term : currentTerms.exclude()) {
            termRows.add(new ScopeTermRow(EXCLUDE_TERM_KEY, term, () -> dropScopeTerm(false, term)));
        }
        for (int i = 0; i < termRows.size(); i++) {
            filterContainer.add(termRows.get(i), i);
        }
        layoutList();
    }

    /** Remove one narrowing or excluded term from the panel's scope; Apply passes the rest on. */
    private void dropScopeTerm(boolean narrowing, String term) {
        List<String> narrow = new ArrayList<>(currentTerms.narrow());
        List<String> exclude = new ArrayList<>(currentTerms.exclude());
        (narrowing ? narrow : exclude).remove(term);
        currentTerms = new ScopeTerms(narrow, exclude);
        log.info("dropScopeTerm: removed {} term for index='{}'", narrowing ? "narrow" : "exclude", currentIndex);
        renderTermRows();
    }

    /**
     * Load the persisted narrowing and excluded terms for an index being entered.
     */
    private ScopeTerms loadPersistedTerms(String index) {
        try {
            ScopeTerms terms = searchManager.getScopeTerms(index);
            return terms != null ? terms : ScopeTerms.EMPTY;
        } catch (Exception e) {
            log.error("loadPersistedTerms: Failed for index '{}'", index, e);
            showNotification("Failed to load saved scope terms: " + ExceptionMessageFormatter.formatException(e),
                    Notifications.Type.ERROR);
            return ScopeTerms.EMPTY;
        }
    }

    /**
     * The scope the panel currently describes: the index with the filter rows' values
     * (in row order, as the generated query lists them) and the saved text terms, or
     * the manual query in advanced mode.
     */
    private Scope buildScope(String manualSpl) {
        Map<String, String> filters = new LinkedHashMap<>();
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (FilterRow row : filterRows) {
            String value = row.getFilterValue();
            if (isNotBlank(value)) {
                filters.put(row.getFieldName(), value);
                if (!row.getAliases().isEmpty()) {
                    aliases.put(row.getFieldName(), row.getAliases());
                }
            }
        }
        String index = indexPicker.getSelectionKey();
        return new Scope(index, filters, currentTerms.narrow(), currentTerms.exclude(), manualSpl, aliases);
    }

    /**
     * Apply {@link #currentFilters} to the filter rows. A row takes the value saved under any of its real
     * fields, the primary one first, so a value saved before the fields were merged still shows.
     */
    private void applyCurrentFilters() {
        int applied = 0;
        for (FilterRow row : filterRows) {
            String value = savedValue(row);
            if (value != null) {
                row.setFilterValue(value);
                applied++;
            }
        }
        log.debug("applyCurrentFilters: Applied {} filters for '{}'", applied, currentIndex);
    }

    private void clearFilterPanel() {
        log.info("clearFilterPanel: Clearing {} filter rows", filterRows.size());
        filterRows.clear();
        termRows.clear();
        filterContainer.removeAll();
        renderTermRows();
        filterContainer.revalidate();
        filterContainer.repaint();
        log.debug("clearFilterPanel: Completed");
    }

    private void clearAllFilters() {
        log.info("clearAllFilters: Clearing {} filter rows", filterRows.size());
        currentFilters.clear();
        for (FilterRow row : filterRows) {
            row.clearValue();
        }

        updateGeneratedQuery();
        log.debug("clearAllFilters: Completed");
    }

    private void updateGeneratedQuery() {
        log.info("updateGeneratedQuery: Updating query");
        StringBuilder query = new StringBuilder();

        String selectedIndex = indexPicker.getSelectionKey();
        if (isNotBlank(selectedIndex)) {
            query.append(IndexSelection.parse(selectedIndex).predicate());
        }

        for (FilterRow row : filterRows) {
            String filter = row.getFilterString();
            if (!filter.isEmpty()) {
                if (!query.isEmpty()) {
                    query.append(" ");
                }
                query.append(filter);
            }
        }

        generatedQueryArea.setText(query.toString());
        applyButton.setEnabled(isNotBlank(query.toString()));
        updateFiltersMeta();
        log.debug("updateGeneratedQuery: Query={}", query);
    }

    /**
     * The filters section's rule text: field and set counts (or "optional" without rows) and the
     * sample the fields come from, e.g. {@code 45 fields · 1 set · newest 10k}.
     */
    private void updateFiltersMeta() {
        StringBuilder meta = new StringBuilder();
        if (filterRows.isEmpty()) {
            meta.append(STEP_2_META_OPTIONAL);
        } else {
            long set = filterRows.stream().filter(row -> isNotBlank(row.getFilterValue())).count();
            meta.append(filterRows.size()).append(" fields");
            if (set > 0) {
                meta.append(META_SEPARATOR).append(set).append(" set");
            }
        }
        int sample = isNotBlank(currentIndex) ? searchManager.fieldSampleSize(currentIndex) : 0;
        if (sample > 0) {
            meta.append(META_SEPARATOR).append("newest ").append(FieldSampling.sizeLabel(sample));
        }
        filtersSection.setMeta(meta.toString());
    }

    private void toggleAdvancedMode() {
        advancedMode = advancedToggle.isSelected();
        log.info("toggleAdvancedMode: Switching to {} mode", advancedMode ? "advanced" : "guided");

        queryHost.removeAll();
        if (advancedMode) {
            manualQueryField.setText(generatedQueryArea.getText());
            queryHost.add(manualQueryField, BorderLayout.CENTER);
            advancedToggle.setForeground(DarculaColors.ORANGE);
        } else {
            queryHost.add(generatedQueryScroll, BorderLayout.CENTER);
            advancedToggle.setForeground(DarculaColors.G7);
        }
        queryHost.revalidate();
        queryHost.repaint();
        if (advancedMode) {
            manualQueryField.requestFocusInWindow();
        }
        log.debug("toggleAdvancedMode: Completed");
    }

    private void handleLogout() {
        log.info("handleLogout: User initiated logout");
        if (onLogout != null) {
            onLogout.run();
        }
        log.debug("handleLogout: Completed");
    }

    private void handleCancel() {
        log.info("handleCancel: Cancelling");
        if (onCancel != null) {
            onCancel.run();
        }
        log.debug("handleCancel: Completed");
    }

    private void handleApply() {
        log.info("handleApply: mode={}", advancedMode ? "advanced" : "guided");
        String query;
        if (advancedMode) {
            query = manualQueryField.getText().trim();
        } else {
            query = generatedQueryArea.getText().trim();
        }

        if (query.isEmpty()) {
            log.debug("handleApply: Empty query, ignoring");
            showNotification("Please specify a query", Notifications.Type.WARNING);
            return;
        }

        Scope newScope = buildScope(advancedMode ? query : null);
        int outside = bookmarkGuard != null ? bookmarkGuard.outside(newScope) : 0;
        if (outside > 0 && !confirmer.confirm(this, BookmarkNotices.outsideDialog(outside))) {
            log.debug("handleApply: {} bookmarks would be dropped, user kept the scope", outside);
            return;
        }

        // Save current filters to SearchFilterManager (persists to database)
        if (isNotBlank(currentIndex)) {
            saveFilters(currentIndex, snapshotFilters());
            saveScopeTerms(currentIndex, currentTerms);
        }

        if (outside > 0) {
            bookmarkGuard.drop(newScope);
        }
        searchManager.setScope(newScope);

        if (onLoginComplete != null) {
            onLoginComplete.accept(searchManager);
        }
        log.debug("handleApply: Completed with query={}", query);
    }

    private void showNotification(String message, Notifications.Type type) {
        notifier.accept(message, type);
    }

    private static void showToast(String message, Notifications.Type type) {
        Notifications.getInstance().show(type, Notifications.Location.TOP_CENTER, message);
    }

    private void setupKeyBindings() {
        // Use WHEN_ANCESTOR_OF_FOCUSED_COMPONENT instead of WHEN_IN_FOCUSED_WINDOW
        // This ensures key bindings only work when IndexPanel or its children have focus
        // Prevents conflicts with main panel's key bindings

        // ESC to cancel
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                ACTION_CANCEL);
        getActionMap().put(ACTION_CANCEL, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleCancel();
            }
        });

        // F5 to refresh
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
                ACTION_REFRESH);
        getActionMap().put(ACTION_REFRESH, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshIndexes();
            }
        });

        // Ctrl+F to find a field
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), ACTION_FIND);
        getActionMap().put(ACTION_FIND, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findField.requestFocusInWindow();
                findField.selectAll();
            }
        });

        // Ctrl+Enter to apply
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), ACTION_APPLY);
        getActionMap().put(ACTION_APPLY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (applyButton.isEnabled()) {
                    handleApply();
                }
            }
        });
    }

    private void updateFilterPanel(SortedSet<FieldSummary> fieldSummaries) {
        log.info("updateFilterPanel: {} field summaries", fieldSummaries != null ? fieldSummaries.size() : 0);
        // Keep edits made since the index was entered; the rebuilt rows get them back
        captureRowValues();
        clearFilterPanel();

        if (fieldSummaries == null || fieldSummaries.isEmpty()) {
            JLabel noFieldsLabel = new JLabel(NO_FIELDS_LABEL);
            noFieldsLabel.setFont(font);
            noFieldsLabel.setForeground(DarculaColors.GREY);
            noFieldsLabel.setHorizontalAlignment(SwingConstants.CENTER);
            filterContainer.add(noFieldsLabel);
        } else {
            addFieldRows(fieldSummaries.stream().filter(FieldSummary::isRelevantForFiltering).toList());
        }

        applyCurrentFilters();
        applyFind();

        // Scroll to the most relevant field: prefer filled-in fields, fall back to highlighted
        scrollToMostRelevantField();

        updateGeneratedQuery();
        log.debug("updateFilterPanel: Completed with {} filter rows", filterRows.size());
    }

    /**
     * Add the field rows in {@link FieldGrouping} order under their headings. Fields pinned at index entry
     * come first under {@value #PINNED_HEADING}, labelled with their full name, and the rest then start
     * under {@value #UNPINNED_HEADING}.
     */
    private void addFieldRows(List<FieldSummary> summaries) {
        List<FieldGrouping.Entry> entries = FieldGrouping.group(summaries);
        List<FieldGrouping.Row> pinned = entries.stream().filter(FieldGrouping.Row.class::isInstance)
                .map(FieldGrouping.Row.class::cast).filter(this::isPinned).toList();
        if (!pinned.isEmpty()) {
            filterContainer.add(new FieldHeading(0, PINNED_HEADING));
            for (FieldGrouping.Row row : pinned) {
                addFilterRow(row.field(), row.field().primary());
            }
            // Without it the ungrouped fields would read as part of the set group
            filterContainer.add(new FieldHeading(0, UNPINNED_HEADING));
        }
        for (FieldGrouping.Entry entry : entries) {
            switch (entry) {
                case FieldGrouping.Heading heading -> filterContainer.add(new FieldHeading(heading.depth(),
                        heading.text()));
                case FieldGrouping.Row row -> {
                    if (!isPinned(row)) {
                        addFilterRow(row.field(), row.label());
                    }
                }
            }
        }
        // A heading whose rows are all pinned has nothing left under it
        hideEmptyHeadings();
    }

    private boolean isPinned(FieldGrouping.Row row) {
        return row.field().fields().stream().anyMatch(pinnedFields::contains);
    }

    private void addFilterRow(LogicalField field, String label) {
        FilterRow filterRow = new FilterRow(field, label, this::updateGeneratedQuery, highlightPatterns);
        filterRows.add(filterRow);
        filterContainer.add(filterRow);
    }

    /** The value saved for a row: under its primary field, else under one of its aliases. */
    private String savedValue(FilterRow row) {
        for (String field : row.getFields()) {
            String value = currentFilters.get(field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Show the rows found by the find field (all when it is empty), then re-stripe and re-measure the list. */
    private void applyFind() {
        String query = findField.getText();
        for (FilterRow row : filterRows) {
            row.setVisible(row.matches(query));
        }
        hideEmptyHeadings();
        layoutList();
    }

    /** Hide each heading that has no visible row before the next heading of the same or a higher level. */
    private void hideEmptyHeadings() {
        Component[] items = filterContainer.getComponents();
        for (int i = 0; i < items.length; i++) {
            if (items[i] instanceof FieldHeading heading) {
                boolean any = false;
                for (int j = i + 1; j < items.length; j++) {
                    if (items[j] instanceof FieldHeading next && next.depth() <= heading.depth()) {
                        break;
                    }
                    if (items[j] instanceof ListRow row && row.isVisible()) {
                        any = true;
                        break;
                    }
                }
                heading.setVisible(any);
            }
        }
    }

    /**
     * One label width for all rows, fitting the longest visible label within bounds, so the values form one
     * column; and stripes on every other visible row, restarting under each heading.
     */
    private void layoutList() {
        int longest = 0;
        for (Component item : filterContainer.getComponents()) {
            if (item instanceof ListRow row && row.isVisible()) {
                longest = Math.max(longest, row.labelPreferredWidth());
            }
        }
        int width = Math.clamp(longest, LABEL_WIDTH_MIN, LABEL_WIDTH_MAX);
        boolean stripe = false;
        for (Component item : filterContainer.getComponents()) {
            if (item instanceof FieldHeading) {
                stripe = false;
            } else if (item instanceof ListRow row) {
                row.setLabelWidth(width);
                if (row.isVisible()) {
                    row.setStripe(stripe);
                    stripe = !stripe;
                }
            }
        }
        filterContainer.revalidate();
        filterContainer.repaint();
    }

    /**
     * Scrolls to the most relevant field in the filter panel.
     * Priority: fields with values (previously selected) > highlighted fields.
     */
    private void scrollToMostRelevantField() {
        FilterRow targetRow = findFirstFilledField();
        if (targetRow == null) {
            targetRow = findFirstHighlightedField();
        }
        if (targetRow != null) {
            scrollFieldIntoView(targetRow);
        }
    }

    /**
     * Finds the first field that has a value filled in.
     */
    private FilterRow findFirstFilledField() {
        for (FilterRow row : filterRows) {
            if (!row.getFilterValue().isEmpty()) {
                log.debug("Found filled field: {}", row.getFieldName());
                return row;
            }
        }
        return null;
    }

    /**
     * Finds the first highlighted field.
     */
    private FilterRow findFirstHighlightedField() {
        for (FilterRow row : filterRows) {
            if (row.isHighlighted()) {
                log.debug("Found highlighted field: {}", row.getFieldName());
                return row;
            }
        }
        return null;
    }

    /**
     * Scrolls the filter panel to make the specified field visible, centered if possible.
     */
    private void scrollFieldIntoView(FilterRow targetRow) {
        SwingUtilities.invokeLater(() -> {
            Rectangle rowBounds = targetRow.getBounds();
            Rectangle viewportBounds = filterScrollPane.getViewport().getViewRect();

            // Calculate centered position
            int centeredY = rowBounds.y - (viewportBounds.height - rowBounds.height) / 2;
            centeredY = Math.max(0, centeredY); // Don't scroll above top

            Rectangle centeredRect = new Rectangle(0, centeredY, viewportBounds.width, viewportBounds.height);
            filterContainer.scrollRectToVisible(centeredRect);

            log.debug("Scrolled to field: {}", targetRow.getFieldName());
        });
    }

    private void updateIndexPicker(SortedSet<String> indexes) {
        log.info("updateIndexPicker: {} indexes", indexes != null ? indexes.size() : 0);
        indexPicker.setIndexes(indexes);
        indexSection.setMeta(indexPicker.choiceCount() + " indexes");
        log.debug("updateIndexPicker: Completed with {} items", indexPicker.choiceCount());
    }

    // Test hooks (EDT only)

    void setNotifier(BiConsumer<String, Notifications.Type> notifier) {
        this.notifier = notifier;
    }

    int indexChoiceCount() {
        return indexPicker.choiceCount();
    }

    void selectIndex(String index) {
        indexPicker.setSelectionKey(index);
    }

    /** The narrowing and excluded term rows currently listed with the field rows. */
    List<ScopeTermRow> scopeTermRows() {
        return List.copyOf(termRows);
    }

    /** Non-blank values currently shown in the filter rows. */
    Map<String, String> filterRowValues() {
        Map<String, String> values = new TreeMap<>();
        for (FilterRow row : filterRows) {
            if (isNotBlank(row.getFilterValue())) {
                values.put(row.getFieldName(), row.getFilterValue());
            }
        }
        return values;
    }

    List<String> filterRowFields() {
        return filterRows.stream().map(FilterRow::getFieldName).toList();
    }

    void setFindText(String text) {
        findField.setText(text);
    }

    /** The primary field names of the rows the find field leaves visible, in list order. */
    List<String> visibleFilterRowFields() {
        return filterRows.stream().filter(Component::isVisible).map(FilterRow::getFieldName).toList();
    }

    /** The visible headings in list order, as {@code depth:text}. */
    List<String> visibleHeadings() {
        return Arrays.stream(filterContainer.getComponents()).filter(Component::isVisible)
                .filter(FieldHeading.class::isInstance).map(FieldHeading.class::cast)
                .map(heading -> heading.depth() + ":" + heading.text()).toList();
    }

    void setFilterRowValue(String field, String value) {
        filterRows.stream().filter(row -> row.getFieldName().equals(field)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No filter row for " + field)).setFilterValue(value);
    }

    String generatedQuery() {
        return generatedQueryArea.getText();
    }

    void apply() {
        handleApply();
    }

    void switchToAdvancedWith(String manualQuery) {
        advancedToggle.doClick();
        manualQueryField.setText(manualQuery);
    }

    ProgressButton fieldRefreshButton() {
        return fieldRefreshButton;
    }

    ProgressButton indexRefreshButton() {
        return indexRefreshButton;
    }
}
