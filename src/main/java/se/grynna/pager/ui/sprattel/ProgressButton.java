package se.grynna.pager.ui.sprattel;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import lombok.extern.slf4j.Slf4j;
import raven.swing.spinner.SpinnerProgress;

import javax.swing.*;
import java.awt.*;

@Slf4j
public class ProgressButton extends JButton {
    private final FlatSVGIcon svgIcon;
    private final SpinnerProgress progressSpinner;
    private final Dimension preferredSize;
    private boolean isRefreshing = false;

    public ProgressButton(String svgPath, String tooltip) {
        try {
            this.preferredSize = new Dimension(20, 20);
            String resourceName = svgPath.startsWith("/") ? svgPath.substring(1) : svgPath;
            this.svgIcon = new FlatSVGIcon(resourceName, (int) preferredSize.getWidth(),
                    (int) preferredSize.getHeight());
            log.debug("Loaded SVG icon: {}", resourceName);
        } catch (Exception e) {
            log.error("Failed to load SVG icon: {}, using fallback", svgPath, e);
            throw new IllegalStateException("Failed to load SVG icon: " + svgPath, e);
        }

        // Initialize spinner progress
        progressSpinner = new SpinnerProgress();
        progressSpinner.setBorderPainted(false);
        progressSpinner.setStringPainted(false);
        progressSpinner.setVisible(false);

        // Set fixed size for the button first
        Dimension buttonSize = new Dimension(30, 30);
        setPreferredSize(buttonSize);
        setMinimumSize(buttonSize);
        setMaximumSize(buttonSize);

        // Use null layout for absolute positioning
        setLayout(null);

        // Set bounds for spinner (centered 20x20 within 30x30 button)
        int spinnerSize = 20;
        int x = (buttonSize.width - spinnerSize) / 2;
        int y = (buttonSize.height - spinnerSize) / 2;
        progressSpinner.setBounds(x, y, spinnerSize, spinnerSize);

        add(progressSpinner);

        setIcon(svgIcon);
        setToolTipText(tooltip);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public void setRefreshing(boolean refreshing) {

        if (this.isRefreshing == refreshing) {
            return; // No change
        }
        this.isRefreshing = refreshing;

        log.debug("=== setRefreshing({}) ===", refreshing);
        log.debug("Button size before: {}", getSize());
        log.debug("Button getPreferredSize: {}", getPreferredSize());
        log.debug("Spinner getPreferredSize: {}", progressSpinner.getPreferredSize());
        log.debug("Spinner size: {}", progressSpinner.getSize());

        if (refreshing) {
            setIcon(null);
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            progressSpinner.setIndeterminate(true);
            progressSpinner.setVisible(true);
            progressSpinner.repaint();
        } else {
            progressSpinner.setVisible(false);
            setIcon(svgIcon);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        log.debug("Button size after: {}", getSize());

        repaint();
    }

    public void setIndeterminate(boolean indeterminate) {
        progressSpinner.setIndeterminate(indeterminate);
        progressSpinner.repaint();
    }

    public void setProgress(float fraction) {
        progressSpinner.setIndeterminate(false);
        progressSpinner.setMinimum(0);
        progressSpinner.setMaximum(100);
        int value = Math.round(fraction * 100);
        progressSpinner.setValue(value);

        progressSpinner.repaint();
    }

    public boolean isRefreshing() {
        return isRefreshing;
    }

    @Override
    public void doLayout() {
        super.doLayout();
        // Manually position the spinner in the center
        if (progressSpinner != null) {
            int spinnerSize = 20;
            int x = (getWidth() - spinnerSize) / 2;
            int y = (getHeight() - spinnerSize) / 2;
            progressSpinner.setBounds(x, y, spinnerSize, spinnerSize);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        // Force consistent size regardless of content
        return new Dimension(30, 30);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        progressSpinner.setEnabled(enabled);
        if (!enabled) {
            setRefreshing(false);
        }
        repaint();
    }

    public void setInnerIcon(Icon icon) {
        progressSpinner.setIcon(icon);
        repaint();
    }
}
