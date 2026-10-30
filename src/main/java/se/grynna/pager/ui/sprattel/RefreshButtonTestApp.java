package se.grynna.pager.ui.sprattel;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Test application for manually testing the RefreshButton component.
 */
@Slf4j
public class RefreshButtonTestApp {

    private ProgressButton refreshButton;
    private JToggleButton toggleRefreshing;
    private JSlider progressSlider;
    private JButton setProgressButton;
    private JLabel statusLabel;
    private JCheckBox darkModeToggle;

    public RefreshButtonTestApp() {
        // Set up the UI
        FlatDarculaLaf.setup();
        JFrame frame = new JFrame("RefreshButton Test");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        FlatSVGIcon icon = new FlatSVGIcon("uparrow.svg", 8, 8);

        // Create a panel for the RefreshButton
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        // Wrap button in a container panel to show its bounds
        JPanel buttonContainer = new JPanel(new BorderLayout());
        buttonContainer.setBackground(Color.CYAN);
        buttonContainer.setBorder(BorderFactory.createLineBorder(Color.RED, 2));

        refreshButton = new ProgressButton("Refresh_icon.svg", "Refresh");

        refreshButton.setInnerIcon(icon);

        buttonContainer.add(refreshButton, BorderLayout.CENTER);
        buttonPanel.add(buttonContainer);

        // Add size label
        JPanel sizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel sizeLabel = new JLabel("Button size: ");
        sizePanel.add(sizeLabel);

        frame.add(buttonPanel, BorderLayout.CENTER);

        // Create a control panel
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.add(sizePanel, 0); // Add at top

        // Track size changes
        refreshButton.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                Dimension size = refreshButton.getSize();
                Dimension containerSize = buttonContainer.getSize();
                sizeLabel.setText(String.format("Button: %dx%d | Container: %dx%d", size.width, size.height,
                        containerSize.width, containerSize.height));
            }
        });

        // Refreshing toggle
        JPanel refreshingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toggleRefreshing = new JCheckBox("Start Refreshing");
        toggleRefreshing.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var isRefreshing = toggleRefreshing.isSelected();
                log.info("Toggling refreshing: {}", isRefreshing);
                refreshButton.setRefreshing(isRefreshing);
                statusLabel.setText("Status: " + (isRefreshing ? "Refreshing" : "Idle"));
            }
        });
        refreshingPanel.add(toggleRefreshing);
        controlPanel.add(refreshingPanel);

        // Progress controls
        JPanel progressPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        progressPanel.add(new JLabel("Progress:"));
        progressSlider = new JSlider(0, 100, 0);
        progressSlider.setMajorTickSpacing(25);
        progressSlider.setMinorTickSpacing(5);
        progressSlider.setPaintTicks(true);
        progressSlider.setPaintLabels(true);
        progressSlider.setPreferredSize(new Dimension(200, progressSlider.getPreferredSize().height));
        progressPanel.add(progressSlider);
        controlPanel.add(progressPanel);

        // Min/Max spinners
        JPanel minMaxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        controlPanel.add(minMaxPanel);

        // Set progress button
        JPanel setProgressPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        setProgressButton = new JButton("Set Progress");
        setProgressButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int value = progressSlider.getValue();
                refreshButton.setProgress((float) (value / 100.0f));
                statusLabel.setText("Status: Progress set to " + value);
            }
        });
        setProgressPanel.add(setProgressButton);
        controlPanel.add(setProgressPanel);

        // Status label
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel("Status: Idle");
        statusPanel.add(statusLabel);
        controlPanel.add(statusPanel);

        // Dark mode toggle
        JPanel themePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        darkModeToggle = new JCheckBox("Dark Mode");
        darkModeToggle.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean isDarkMode = darkModeToggle.isSelected();
                try {
                    UIManager.setLookAndFeel(isDarkMode ? new FlatDarkLaf() : new FlatLightLaf());
                    SwingUtilities.updateComponentTreeUI(frame);
                } catch (UnsupportedLookAndFeelException ex) {
                    log.error("Failed to switch look and feel to {}", isDarkMode ? "dark" : "light", ex);
                }
            }
        });
        themePanel.add(darkModeToggle);
        controlPanel.add(themePanel);

        frame.add(controlPanel, BorderLayout.SOUTH);

        // Display the window
        frame.setSize(400, 400);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new RefreshButtonTestApp();
            }
        });
    }
}
