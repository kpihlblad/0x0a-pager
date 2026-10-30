package se.grynna.pager.ui.sprattel;

import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;

public class LoadingAnimationTimer {

    private static final int TIMER_DELAY = 50; // Milliseconds
    private static final float ANIMATION_SPEED = 0.033f; // Fraction per timer tick

    private final Timer spinnerTimer;
    private String[] spinnerSteps = {"⢎⡰", "⢎⡡", "⢎⡑", "⢎⠱", "⠎⡱", "⢊⡱", "⢌⡱", "⢆⡱"};
    private float f; // Fraction of the animation
    String dots = "";

    private int spinnerIndex = 0;
    private boolean isLoading;

    // private String stopped  = "⢎⡱";
    public static final String STOPPED = "●";
    private volatile String spinner = STOPPED;

    public LoadingAnimationTimer(Component toRepaint, String message) {
        spinnerTimer = new Timer(TIMER_DELAY, e -> {
            spinner = spinnerSteps[spinnerIndex];
            dots = StringUtils.repeat(".", spinnerIndex % 3);
            spinnerIndex = (spinnerIndex + 1) % spinnerSteps.length;
            f = (f + ANIMATION_SPEED) % 1.0f;

            if (toRepaint instanceof JLabel label) {
                label.setText(spinner + " " + message + dots);
            } else {
                toRepaint.repaint();
            }
        });
    }

    public void loadingIndicator(boolean isLoading) {

        if (this.isLoading == isLoading) {
            return;
        }
        this.isLoading = isLoading;
        if (isLoading) {
            spinnerTimer.start();
        } else {
            spinnerTimer.stop();
            spinner = STOPPED;
        }
    }

    public float getF() {
        return f;
    }
}
