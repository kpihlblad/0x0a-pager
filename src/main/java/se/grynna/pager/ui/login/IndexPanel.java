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
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchProgress;
import se.grynna.pager.splunk.SplQuery;
import se.grynna.pager.ui.BlockCaret;
import se.grynna.pager.ui.BookmarkNotices;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.KeyFlavour;
import se.grynna.pager.ui.KeyMap;
import se.grynna.pager.ui.LogViewer;
import se.grynna.pager.ui.login.FieldSuggestions.SuggestedField;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.concurrent.CompletableFuture.runAsync;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Scope configuration panel: index picker, Narrow and Exclude token lines with autocompletion, suggested fields
 * grid, all-fields popup, and generated or manual SPL query box.
 * <p>
 * The panel edits a single immutable {@link Scope} draft. Every edit replaces the draft via the {@link Scope} API
 * and updates the token lines, suggested fields grid, and query box on the EDT.
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
    private static final String STEP_2_LABEL = "Suggested fields";
    private static final String STEP_2_META_OPTIONAL = "optional";
    private static final String META_SEPARATOR = " \u00B7 ";
    private static final String STEP_3_LABEL = "Query";
    private static final String PROMPT = "\u276F";
    private static final String REFRESHING_INDEXES_LABEL = "Refreshing indexes...";
    private static final String ADVANCED_MODE_BUTTON_TEXT = "advanced";
    private static final String CANCEL_BUTTON_TEXT = "cancel";
    private static final String APPLY_BUTTON_TEXT = "apply";
    private static final String LOADING_FIELDS_LABEL = "Loading field summaries...";
    private static final String NO_FIELDS_LABEL = "No fields available for this index.";

    private static final String NARROW_LABEL = "Narrow";
    private static final String EXCLUDE_LABEL = "Exclude";
    private static final String NARROW_PLACEHOLDER = "type field, value or text\u2026";
    private static final String EXCLUDE_PLACEHOLDER = "type field, value or text to exclude\u2026";

    private static final String REFRESH_ICON_PATH = "/Refresh_icon.svg";
    private static final String REFRESH_INDEXES_TOOLTIP = "Refresh indexes";
    private static final String REFRESH_FIELDS_TOOLTIP = "Look deeper: sample 10\u00D7 more events, further back";
    private static final String SWEEP_ICON_PATH = "/sweep.svg";
    private static final String CLEAR_FILTERS_TOOLTIP = "Clear all field filters and excludes";

    private static final Dimension SUGGESTED_SCROLL_PANE_SIZE = new Dimension(0, 185);
    private static final int GENERATED_QUERY_AREA_ROWS = 3;
    private static final float SMALL_FONT_SIZE = 11f;

    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_REFRESH = "refresh";
    private static final String ACTION_APPLY = "apply";
    private static final String ACTION_ALL_FIELDS_ALT = "all-fields-alt";
    private static final String ACTION_ALL_FIELDS_EMACS = "all-fields-emacs";

    private final Config config;
    private final SearchManager searchManager;

    private boolean advancedMode = false;
    private String currentIndex = null;
    private Scope draft = Scope.ofValues(null, Map.of());
    private List<FieldGrouping.Entry> groupedEntries = List.of();
    private List<SuggestedField> allFields = List.of();

    private BiConsumer<String, Notifications.Type> notifier = IndexPanel::showToast;

    @Setter
    private Confirmer confirmer = Confirmer.dialog();
    @Setter
    private BookmarkScopeGuard bookmarkGuard;

    private IndexPicker indexPicker;
    private Font font;
    private Font boldFont;
    private Font smallFont;

    private ProgressButton indexRefreshButton;
    private ProgressButton fieldRefreshButton;
    private JLabel indexRefreshLabel;

    private Section indexSection;
    private Section filtersSection;
    private Section querySection;

    private TokenLine narrowLine;
    private TokenLine excludeLine;
    private SuggestedFieldsPanel suggestedFieldsPanel;
    private JScrollPane filterScrollPane;
    private JButton allFieldsButton;
    private JButton clearFiltersButton;
    private AllFieldsPopup allFieldsPopup;
    private JPopupMenu allFieldsPopupMenu;

    private JTextField manualQueryField;
    private JTextArea generatedQueryArea;
    private JScrollPane generatedQueryScroll;
    private JPanel queryHost;
    private JToggleButton advancedToggle;

    private JButton applyButton;
    private JButton cancelButton;

    @Setter
    private Runnable onLogout;

    @Setter
    private Consumer<SearchManager> onLoginComplete;

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
            public void onScopeFieldSummariesUpdated(Scope scope, SortedSet<FieldSummary> fieldSummaries,
                    boolean fromCache) {
                IndexPanel.this.onFieldSummariesUpdated(scope, fieldSummaries, fromCache);
            }

            @Override
            public void onFieldSummaryRefreshFailed(String index, Throwable error) {
                IndexPanel.this.onFieldSummaryRefreshFailed(index, error);
            }

            @Override
            public void onScopeFieldSummaryRefreshFailed(Scope scope, Throwable error) {
                IndexPanel.this.onFieldSummaryRefreshFailed(scope, error);
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

    private void onIndexProgress(SearchProgress progress) {
        log.debug("onIndexProgress: progress={}", progress);
        SwingUtilities.invokeLater(() -> onProgress(progress, indexRefreshButton));
    }

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
            updateIndexPicker(indexes);
            indexPicker.setEnabled(true);

            if (!fromCache) {
                indexRefreshButton.setRefreshing(false);
            }
        });
    }

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

        narrowLine = new TokenLine(NARROW_LABEL, NARROW_PLACEHOLDER, true, this::applyDraftEdit);
        excludeLine = new TokenLine(EXCLUDE_LABEL, EXCLUDE_PLACEHOLDER, false, this::applyDraftEdit);

        suggestedFieldsPanel = new SuggestedFieldsPanel(this::applyDraftEdit, this::openAllFieldsPopup,
                excludeLine::focusInput);

        narrowLine.setOnMoveDown(excludeLine::focusInput);
        narrowLine.setOnMoveUp(() -> indexPicker.requestFocusInWindow());
        narrowLine.setOnOpenAllFields(() -> openAllFieldsPopup(""));
        narrowLine.setOnCancel(this::handleCancel);

        excludeLine.setOnMoveUp(narrowLine::focusInput);
        excludeLine.setOnMoveDown(suggestedFieldsPanel::focusFirstRow);
        excludeLine.setOnOpenAllFields(() -> openAllFieldsPopup(""));
        excludeLine.setOnCancel(this::handleCancel);

        JPanel tokenLinesPanel = new JPanel();
        tokenLinesPanel.setLayout(new BoxLayout(tokenLinesPanel, BoxLayout.Y_AXIS));
        tokenLinesPanel.setOpaque(false);
        tokenLinesPanel.setBorder(new EmptyBorder(0, 0, 6, 0));
        tokenLinesPanel.add(narrowLine);
        tokenLinesPanel.add(excludeLine);

        JPanel topGroup = new JPanel(new BorderLayout());
        topGroup.setOpaque(false);
        topGroup.add(indexSection, BorderLayout.NORTH);
        topGroup.add(tokenLinesPanel, BorderLayout.SOUTH);

        filtersSection = new Section(STEP_2_LABEL, font, createSuggestedScrollPane(), createFilterTools());
        filtersSection.setMeta(STEP_2_META_OPTIONAL);
        querySection = new Section(STEP_3_LABEL, font, createQueryBox(), createAdvancedToggle());

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(DarculaColors.BACKGROUND);
        content.setBorder(BORDER_CONTENT);
        content.add(topGroup, BorderLayout.NORTH);
        content.add(filtersSection, BorderLayout.CENTER);
        content.add(querySection, BorderLayout.SOUTH);

        add(createHeaderPanel(), BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(createFooterPanel(), BorderLayout.SOUTH);

        setupKeyBindings();
        revalidate();
        log.debug("Created initComponents");
    }

    private JPanel createHeaderPanel() {
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

    private <T extends AbstractButton> T flatButton(T button) {
        button.setFont(smallFont);
        button.setForeground(DarculaColors.G7);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusPainted(false);
        return button;
    }

    private JPanel createIndexSelectPanel() {
        JPanel indexSelectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        indexSelectPanel.setOpaque(false);

        indexPicker = new IndexPicker();
        indexPicker.setOnChange(this::onIndexSelected);

        indexRefreshButton = new ProgressButton(REFRESH_ICON_PATH, REFRESH_INDEXES_TOOLTIP);
        indexRefreshButton.addActionListener(e -> refreshIndexes());

        indexRefreshLabel = new JLabel(REFRESHING_INDEXES_LABEL);
        indexRefreshLabel.setFont(smallFont);
        indexRefreshLabel.setForeground(DarculaColors.G7);
        indexRefreshLabel.setVisible(false);

        indexSelectPanel.add(indexPicker);
        indexSelectPanel.add(indexRefreshButton);
        indexSelectPanel.add(indexRefreshLabel);
        return indexSelectPanel;
    }

    private JComponent[] createFilterTools() {
        String keyLabel = allFieldsKeyLabel();
        allFieldsButton = flatButton(new JButton("all fields (" + keyLabel + ")"));
        allFieldsButton.setToolTipText("Show all fields (" + keyLabel + ")");
        allFieldsButton.addActionListener(e -> openAllFieldsPopup(""));

        clearFiltersButton = new ProgressButton(SWEEP_ICON_PATH, CLEAR_FILTERS_TOOLTIP);
        clearFiltersButton.addActionListener(e -> clearAllFilters());

        fieldRefreshButton = new ProgressButton(REFRESH_ICON_PATH, REFRESH_FIELDS_TOOLTIP);
        fieldRefreshButton.addActionListener(e -> refreshFields(currentIndex, true, "UserClicked"));
        return new JComponent[]{allFieldsButton, clearFiltersButton, fieldRefreshButton};
    }

    private JScrollPane createSuggestedScrollPane() {
        filterScrollPane = new JScrollPane(suggestedFieldsPanel);
        filterScrollPane.setBackground(DarculaColors.BACKGROUND);
        filterScrollPane.getViewport().setBackground(DarculaColors.BACKGROUND);
        filterScrollPane.setBorder(BorderFactory.createEmptyBorder());
        filterScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        filterScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        filterScrollPane.setPreferredSize(SUGGESTED_SCROLL_PANE_SIZE);
        filterScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return filterScrollPane;
    }

    private JPanel createQueryBox() {
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

        manualQueryField = new JTextField();
        manualQueryField.setFont(font);
        manualQueryField.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        manualQueryField.setForeground(DarculaColors.FOREGROUND);
        manualQueryField.setBorder(BORDER_QUERY_TEXT);
        manualQueryField.setCaret(new BlockCaret(() -> DarculaColors.ORANGE));

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
        return box;
    }

    private JToggleButton createAdvancedToggle() {
        advancedToggle = flatButton(new JToggleButton(ADVANCED_MODE_BUTTON_TEXT));
        advancedToggle.addActionListener(e -> toggleAdvancedMode());
        return advancedToggle;
    }

    private JPanel createFooterPanel() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, DarculaColors.G3), BORDER_FOOTER));

        KeyHintBar hints = new KeyHintBar(font,
                new KeyHintBar.Hint("F5", "refresh"),
                new KeyHintBar.Hint(allFieldsKeyLabel(), "all fields"),
                new KeyHintBar.Hint("-value", "exclude"));

        cancelButton = new KeyButton("Esc", CANCEL_BUTTON_TEXT, font, false);
        cancelButton.addActionListener(e -> handleCancel());

        applyButton = new KeyButton("Ctrl+Enter", APPLY_BUTTON_TEXT, font, true);
        applyButton.addActionListener(e -> handleApply());
        applyButton.setEnabled(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(applyButton);

        JPanel hintsHost = new JPanel(new GridBagLayout());
        hintsHost.setOpaque(false);
        hintsHost.add(hints);

        footer.add(hintsHost, BorderLayout.WEST);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    /** Key label for opening the all-fields popup in the preferred {@link KeyFlavour}. */
    static String allFieldsKeyLabel() {
        return allFieldsKeyLabel(KeyMap.preferredFlavour());
    }

    static String allFieldsKeyLabel(KeyFlavour flavour) {
        if (flavour == null) {
            return "Alt+Down";
        }
        return switch (flavour) {
            case DESKTOP -> "Alt+Down";
            case PAGER -> "Tab Tab";
            case EMACS -> "Ctrl+Alt+I";
        };
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

        if (allFields.isEmpty()) {
            suggestedFieldsPanel.updateState(List.of(), draft, LOADING_FIELDS_LABEL);
        }
        suggestedFieldsPanel.setLoading(true);

        try {
            Scope targetScope = draft != null && selectedIndex.equals(draft.index())
                    ? draft
                    : Scope.ofValues(selectedIndex, Map.of());
            SortedSet<FieldSummary> summaries = loadOrDeepenSummaries(targetScope, forceRefresh);
            updateFiltersMeta();
            if (summaries != null && (!summaries.isEmpty() || "onIndexSelected".equals(reason))) {
                onFieldSummariesUpdated(targetScope, summaries, true);
            }
        } catch (RuntimeException e) {
            suggestedFieldsPanel.setLoading(false);
            log.error("refreshFields: Failed to load field summaries for index '{}'", selectedIndex, e);
            showNotification("Failed to load field summaries: " + ExceptionMessageFormatter.formatException(e),
                    Notifications.Type.ERROR);
        }
    }

    private SortedSet<FieldSummary> loadOrDeepenSummaries(Scope scope, boolean forceRefresh) {
        String index = scope.index();
        SortedSet<FieldSummary> summaries = forceRefresh
                ? searchManager.deepenFieldSummariesForScope(scope)
                : searchManager.loadFieldSummariesForScope(scope, false);
        if ((summaries == null || summaries.isEmpty()) && index != null && (forceRefresh || allFields.isEmpty())) {
            summaries = forceRefresh
                    ? searchManager.deepenFieldSummaries(index)
                    : searchManager.loadFieldSummaries(index, false);
        }
        return summaries != null ? summaries : Collections.emptySortedSet();
    }

    public void onFieldSummariesUpdated(String index, SortedSet<FieldSummary> fieldSummaries, boolean fromCache) {
        SwingUtilities.invokeLater(() -> {
            if (!index.equals(currentIndex)) {
                log.debug("Dropping field update for '{}': current index is '{}'", index, currentIndex);
                return;
            }
            applyFieldSummariesUpdate(index, fieldSummaries, fromCache);
        });
    }

    public void onFieldSummariesUpdated(Scope scope, SortedSet<FieldSummary> fieldSummaries, boolean fromCache) {
        SwingUtilities.invokeLater(() -> {
            if (scope == null || !matchesCurrentDraft(scope)) {
                log.debug("Dropping field update for '{}': current draft is '{}'",
                        scope == null ? null : SplQuery.scope(scope),
                        draft == null ? null : SplQuery.scope(draft));
                return;
            }
            applyFieldSummariesUpdate(scope.index(), fieldSummaries, fromCache);
        });
    }

    private void applyFieldSummariesUpdate(String index, SortedSet<FieldSummary> fieldSummaries, boolean fromCache) {
        log.debug("Received field update for '{}': {} fields (fromCache: {})", index, fieldSummaries.size(),
                fromCache);
        if (!fromCache || !fieldSummaries.isEmpty()) {
            suggestedFieldsPanel.setLoading(false);
        }
        updateFilterPanel(fieldSummaries);

        if (!fromCache) {
            fieldRefreshButton.setRefreshing(false);
        }
    }

    private boolean matchesCurrentDraft(Scope scope) {
        if (currentIndex == null || !currentIndex.equals(scope.index()) || draft == null) {
            return false;
        }
        return SplQuery.scope(scope).equals(SplQuery.scope(draft));
    }

    private void onFieldSummaryRefreshFailed(String index, Throwable error) {
        SwingUtilities.invokeLater(() -> {
            if (!index.equals(currentIndex)) {
                log.debug("Dropping field refresh failure for '{}': current index is '{}'", index, currentIndex);
                return;
            }
            applyFieldSummaryRefreshFailed(index, error);
        });
    }

    private void onFieldSummaryRefreshFailed(Scope scope, Throwable error) {
        SwingUtilities.invokeLater(() -> {
            if (scope == null || !matchesCurrentDraft(scope)) {
                log.debug("Dropping field refresh failure for '{}': current draft is '{}'",
                        scope == null ? null : SplQuery.scope(scope),
                        draft == null ? null : SplQuery.scope(draft));
                return;
            }
            applyFieldSummaryRefreshFailed(scope.index(), error);
        });
    }

    private void applyFieldSummaryRefreshFailed(String index, Throwable error) {
        log.warn("Field summary refresh failed for '{}': {}", index, error.toString());
        fieldRefreshButton.setRefreshing(false);
        suggestedFieldsPanel.setLoading(false);
        if (allFields.isEmpty()) {
            updateFilterPanel(Collections.emptySortedSet());
        }
        showNotification("Failed to load field summaries: " + ExceptionMessageFormatter.formatException(error),
                Notifications.Type.ERROR);
    }

    private void onIndexSelected(String key) {
        String selectedIndex = isBlank(key) ? null : key;

        if (currentIndex == null && selectedIndex == null) {
            return;
        }
        if (currentIndex != null && currentIndex.equals(selectedIndex)) {
            return;
        }
        log.info("onIndexSelected: Changed from '{}' to '{}'", currentIndex, selectedIndex);

        if (isNotBlank(currentIndex)) {
            saveFilters(currentIndex, draft.fieldFilters());
            saveScopeTerms(currentIndex, new ScopeTerms(draft.narrow(), draft.exclude()));
        }

        currentIndex = selectedIndex;
        groupedEntries = List.of();
        allFields = List.of();
        closeAllFieldsPopup();

        if (isBlank(selectedIndex)) {
            draft = Scope.ofValues(null, Map.of());
            suggestedFieldsPanel.setLoading(false);
            syncUiFromDraft(NO_FIELDS_LABEL);
            return;
        }

        Map<String, List<String>> filters = loadPersistedFilters(selectedIndex);
        ScopeTerms terms = loadPersistedTerms(selectedIndex);
        draft = new Scope(selectedIndex, filters, terms.narrow(), terms.exclude(), null, Map.of());
        syncUiFromDraft(LOADING_FIELDS_LABEL);

        var idx = selectedIndex;
        runAsync(() -> searchManager.setLastIndex(idx)).exceptionally(error -> {
            log.error("onIndexSelected: Failed to store last index '{}'", idx, error);
            return null;
        });

        refreshFields(selectedIndex, false, "onIndexSelected");
        updateGeneratedQuery();
    }

    private Scope applyDraftEdit(Scope updated) {
        String previousQuery = draft == null || isBlank(draft.index()) ? "" : SplQuery.scope(draft);
        List<LogicalField> logicals = allFields.stream().map(SuggestedField::field).toList();
        draft = FieldSuggestions.reconcileAliases(updated, logicals);
        syncUiFromDraft(allFields.isEmpty() ? LOADING_FIELDS_LABEL : NO_FIELDS_LABEL);
        String nextQuery = draft == null || isBlank(draft.index()) ? "" : SplQuery.scope(draft);
        if (isNotBlank(currentIndex) && !nextQuery.equals(previousQuery)) {
            refreshFields(currentIndex, false, "filterChanged");
        }
        return draft;
    }

    private void syncUiFromDraft(String emptyStateMessage) {
        narrowLine.updateState(draft, allFields);
        excludeLine.updateState(draft, allFields);
        List<SuggestedField> suggested = FieldSuggestions.suggestFromEntries(groupedEntries, draft);
        suggestedFieldsPanel.updateState(suggested, draft, emptyStateMessage);
        if (allFieldsPopup != null) {
            allFieldsPopup.updateState(groupedEntries, draft);
        }
        updateGeneratedQuery();
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void saveFilters(String index, Map<String, List<String>> filters) {
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

    private Map<String, List<String>> loadPersistedFilters(String index) {
        try {
            return new HashMap<>(searchManager.getFieldFilters(index));
        } catch (Exception e) {
            log.error("loadPersistedFilters: Failed for index '{}'", index, e);
            showNotification("Failed to load cached field filters: " + ExceptionMessageFormatter.formatException(e),
                    Notifications.Type.ERROR);
            return new HashMap<>();
        }
    }

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

    private Scope buildScope(String manualSpl) {
        String index = indexPicker.getSelectionKey();
        if (manualSpl != null) {
            return new Scope(index, draft.fieldFilters(), draft.narrow(), draft.exclude(), manualSpl,
                    draft.fieldAliases());
        }
        return new Scope(index, draft.fieldFilters(), draft.narrow(), draft.exclude(), null, draft.fieldAliases());
    }

    private void clearAllFilters() {
        List<String> keptExclude = draft.exclude().stream()
                .filter(term -> !se.grynna.pager.splunk.FieldTerm.isFieldTerm(term))
                .toList();
        applyDraftEdit(new Scope(draft.index(), Map.of(), draft.narrow(), keptExclude, draft.manualSpl(), Map.of()));
    }

    private String generatedPredicate() {
        if (draft == null) {
            return "";
        }
        String indexPred = draft.indexSelection().predicate();
        if (indexPred.isEmpty()) {
            return "";
        }
        StringBuilder predicate = new StringBuilder(indexPred);
        draft.fieldFilters().forEach((field, values) -> {
            List<String> nonBlank = values.stream().filter(v -> v != null && !v.isBlank()).toList();
            if (!nonBlank.isEmpty()) {
                if (!predicate.isEmpty()) {
                    predicate.append(' ');
                }
                predicate.append(SplQuery.fieldEqualsAny(draft.filterFields(field), nonBlank));
            }
        });
        return predicate.toString();
    }

    private void updateGeneratedQuery() {
        String predicate = generatedPredicate();
        String query = predicate.isEmpty()
                ? ""
                : predicate + SplQuery.termStages(draft.narrow(), draft.exclude());

        generatedQueryArea.setText(query);
        applyButton.setEnabled(isNotBlank(predicate));
        updateFiltersMeta();
    }

    private void updateFiltersMeta() {
        StringBuilder meta = new StringBuilder();
        if (allFields.isEmpty()) {
            meta.append(STEP_2_META_OPTIONAL);
        } else {
            meta.append(allFields.size()).append(" fields");
        }
        int sample = isNotBlank(currentIndex) ? searchManager.fieldSampleSize(currentIndex) : 0;
        if (sample > 0) {
            meta.append(META_SEPARATOR).append("newest ").append(FieldSampling.sizeLabel(sample));
        }
        filtersSection.setMeta(meta.toString());
    }

    private void toggleAdvancedMode() {
        advancedMode = advancedToggle.isSelected();
        queryHost.removeAll();
        if (advancedMode) {
            manualQueryField.setText(generatedPredicate());
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
    }

    private void handleLogout() {
        if (onLogout != null) {
            onLogout.run();
        }
    }

    /** Cancels the scope panel and runs its cancel callback. */
    public void cancel() {
        handleCancel();
    }

    private void handleCancel() {
        log.info("handleCancel: Cancelling");
        suggestedFieldsPanel.setLoading(false);
        closeAllFieldsPopup();
        if (onCancel != null) {
            onCancel.run();
        }
        log.debug("handleCancel: Completed");
    }

    private void handleApply() {
        String query = advancedMode ? manualQueryField.getText().trim() : generatedQueryArea.getText().trim();
        if (query.isEmpty()) {
            showNotification("Please specify a query", Notifications.Type.WARNING);
            return;
        }

        Scope newScope = buildScope(advancedMode ? query : null);
        int outside = bookmarkGuard != null ? bookmarkGuard.outside(newScope) : 0;
        if (outside > 0 && !confirmer.confirm(this, BookmarkNotices.outsideDialog(outside))) {
            return;
        }

        if (isNotBlank(currentIndex)) {
            saveFilters(currentIndex, draft.fieldFilters());
            saveScopeTerms(currentIndex, new ScopeTerms(draft.narrow(), draft.exclude()));
        }

        if (outside > 0) {
            bookmarkGuard.drop(newScope);
        }
        suggestedFieldsPanel.setLoading(false);
        searchManager.setScope(newScope);

        if (onLoginComplete != null) {
            onLoginComplete.accept(searchManager);
        }
    }

    private void showNotification(String message, Notifications.Type type) {
        notifier.accept(message, type);
    }

    private static void showToast(String message, Notifications.Type type) {
        Notifications.getInstance().show(type, Notifications.Location.TOP_CENTER, message);
    }

    private void setupKeyBindings() {
        InputMap im = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_CANCEL);
        am.put(ACTION_CANCEL, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (allFieldsPopupMenu != null && allFieldsPopupMenu.isVisible()) {
                    closeAllFieldsPopup();
                } else {
                    handleCancel();
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), ACTION_REFRESH);
        am.put(ACTION_REFRESH, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshIndexes();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), ACTION_ALL_FIELDS_ALT);
        am.put(ACTION_ALL_FIELDS_ALT, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAllFieldsPopup("");
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                ACTION_ALL_FIELDS_EMACS);
        am.put(ACTION_ALL_FIELDS_EMACS, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAllFieldsPopup("");
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), ACTION_APPLY);
        am.put(ACTION_APPLY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (applyButton.isEnabled()) {
                    handleApply();
                }
            }
        });
    }

    private void updateFilterPanel(SortedSet<FieldSummary> fieldSummaries) {
        groupedEntries = fieldSummaries == null || fieldSummaries.isEmpty()
                ? List.of()
                : FieldGrouping.group(fieldSummaries);
        allFields = FieldSuggestions.allFields(groupedEntries);
        List<LogicalField> logicals = allFields.stream().map(SuggestedField::field).toList();
        draft = FieldSuggestions.reconcileAliases(draft, logicals);
        syncUiFromDraft(suggestedFieldsPanel.isLoading() ? LOADING_FIELDS_LABEL : NO_FIELDS_LABEL);
    }

    AllFieldsPopup openAllFieldsPopup(String initialFilter) {
        if (allFieldsPopup == null) {
            allFieldsPopup = new AllFieldsPopup(this::applyDraftEdit, this::closeAllFieldsPopup);
        }
        allFieldsPopup.updateState(groupedEntries, draft);
        allFieldsPopup.setFilterText(initialFilter == null ? "" : initialFilter);

        if (isShowing()) {
            if (allFieldsPopupMenu == null) {
                allFieldsPopupMenu = new JPopupMenu();
                allFieldsPopupMenu.setFocusable(true);
                allFieldsPopupMenu.setBorder(BorderFactory.createLineBorder(DarculaColors.G3));
                allFieldsPopupMenu.setBackground(DarculaColors.BACKGROUND_OUTSIDE);
                allFieldsPopupMenu.add(allFieldsPopup);
            }
            allFieldsPopupMenu.show(filtersSection, 14, 24);
            SwingUtilities.invokeLater(allFieldsPopup::focusFilterField);
        }
        return allFieldsPopup;
    }

    private void closeAllFieldsPopup() {
        if (allFieldsPopupMenu != null && allFieldsPopupMenu.isVisible()) {
            allFieldsPopupMenu.setVisible(false);
        }
    }

    private void updateIndexPicker(SortedSet<String> indexes) {
        indexPicker.setIndexes(indexes);
        indexSection.setMeta(indexPicker.choiceCount() + " indexes");
    }

    // --- Package-private test hooks (EDT only) ---

    void setNotifier(BiConsumer<String, Notifications.Type> notifier) {
        this.notifier = notifier;
    }

    int indexChoiceCount() {
        return indexPicker.choiceCount();
    }

    void selectIndex(String index) {
        indexPicker.setSelectionKey(index);
    }

    Scope draft() {
        return draft;
    }

    TokenLine narrowLine() {
        return narrowLine;
    }

    TokenLine excludeLine() {
        return excludeLine;
    }

    SuggestedFieldsPanel suggestedFieldsPanel() {
        return suggestedFieldsPanel;
    }

    AllFieldsPopup allFieldsPopup() {
        if (allFieldsPopup == null) {
            allFieldsPopup = new AllFieldsPopup(this::applyDraftEdit, this::closeAllFieldsPopup);
            allFieldsPopup.updateState(groupedEntries, draft);
        }
        return allFieldsPopup;
    }

    List<String> suggestedFieldNames() {
        return suggestedFieldsPanel.rows().stream().map(SuggestedField::primary).toList();
    }

    void narrowFieldValue(String field, String value) {
        for (SuggestedField sf : allFields) {
            if (sf.field().fields().contains(field) || sf.label().equalsIgnoreCase(field)) {
                applyDraftEdit(FieldSuggestions.withNarrowFieldValue(draft, sf.field(), value));
                return;
            }
        }
        applyDraftEdit(draft.withFieldValue(field, value));
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
