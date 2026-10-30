package se.grynna.pager.ui;

import se.grynna.pager.export.ExportController;
import se.grynna.pager.export.ExportFormat;
import se.grynna.pager.ui.ExportSources.Source;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Modal Export dialog: the source (unavailable sources are disabled), the format, then a file chooser
 * for the file. It only collects the choices and hands them to {@code onExport}; the caller writes.
 */
public final class ExportDialog extends JDialog {

    /** What the user chose: the events, the format and the file to write. */
    public record Choice(Source source, ExportFormat format, Path file) {}

    private final Map<Source, JRadioButton> sourceButtons = new EnumMap<>(Source.class);
    private final JComboBox<ExportFormat> formatBox = new JComboBox<>(ExportFormat.values());
    private final JButton exportButton = new JButton("Export…");
    private final String index;
    private final Consumer<Choice> onExport;

    /**
     * @param available the sources that have events; the others are shown disabled
     * @param index     the Splunk index, for the suggested file name
     * @param onExport  receives the choice once a file is picked; called on the EDT
     */
    public ExportDialog(Window owner, Set<Source> available, String index, Consumer<Choice> onExport) {
        this(owner, available, index, null, onExport);
    }

    /**
     * @param preselected the source to select first when it is available; null selects the first available
     */
    public ExportDialog(Window owner, Set<Source> available, String index, Source preselected,
            Consumer<Choice> onExport) {
        super(owner, "Export events", ModalityType.APPLICATION_MODAL);
        this.index = index;
        this.onExport = onExport;

        ButtonGroup group = new ButtonGroup();
        JPanel sources = new JPanel(new GridLayout(0, 1));
        sources.add(new JLabel("Events"));
        for (Source source : Source.values()) {
            JRadioButton button = new JRadioButton(label(source));
            button.setEnabled(available.contains(source));
            group.add(button);
            sourceButtons.put(source, button);
            sources.add(button);
        }
        selectFirstAvailable(available, preselected);

        formatBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int row, boolean selected,
                    boolean focus) {
                super.getListCellRendererComponent(list, value, row, selected, focus);
                if (value instanceof ExportFormat format) {
                    setText(format.displayName() + "  ·  " + format.description());
                }
                return this;
            }
        });

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        exportButton.addActionListener(e -> exportChosen());
        exportButton.setEnabled(!available.isEmpty());
        getRootPane().setDefaultButton(exportButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel form = new JPanel(new BorderLayout(12, 12));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        form.add(sources, BorderLayout.NORTH);
        JPanel format = new JPanel(new BorderLayout(8, 0));
        format.add(new JLabel("Format"), BorderLayout.WEST);
        format.add(formatBox, BorderLayout.CENTER);
        form.add(format, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(exportButton);
        form.add(buttons, BorderLayout.SOUTH);

        setContentPane(form);
        pack();
        setResizable(false);
    }

    private static String label(Source source) {
        return switch (source) {
            case SELECTION -> "Selection";
            case MATCHES -> "All matches";
            case BOOKMARKS -> "Bookmarks";
        };
    }

    private void selectFirstAvailable(Set<Source> available, Source preselected) {
        if (preselected != null && available.contains(preselected)) {
            sourceButtons.get(preselected).setSelected(true);
            return;
        }
        for (Source source : Source.values()) {
            if (available.contains(source)) {
                sourceButtons.get(source).setSelected(true);
                return;
            }
        }
    }

    private Source selectedSource() {
        return sourceButtons.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey).findFirst().orElse(Source.SELECTION);
    }

    private void exportChosen() {
        ExportFormat format = (ExportFormat) formatBox.getSelectedItem();
        Path file = chooseFile(format);
        if (file == null) {
            return;
        }
        Source source = selectedSource();
        dispose();
        onExport.accept(new Choice(source, format, file));
    }

    /** The file chooser for {@code format}; null when the user cancels. */
    private Path chooseFile(ExportFormat format) {
        String extension = format.fileExtension();
        JFileChooser chooser = new JFileChooser() {
            @Override
            public void approveSelection() {
                File file = getSelectedFile();
                if (file == null || file.isDirectory()) {
                    super.approveSelection();
                    return;
                }
                if (!file.getName().endsWith(extension)) {
                    file = new File(file.getParentFile(), file.getName() + extension);
                    setSelectedFile(file);
                }
                if (file.exists() && JOptionPane.showConfirmDialog(this, "Replace " + file.getName() + "?",
                        "Export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                    return;
                }
                super.approveSelection();
            }
        };
        chooser.setFileFilter(new FileNameExtensionFilter(format.displayName() + " (*" + extension + ")",
                extension.substring(1)));
        chooser.setSelectedFile(new File(ExportController.defaultFileName(index, format, Instant.now(),
                DateTimeFormatterManager.zoneId())));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile().toPath();
    }
}
