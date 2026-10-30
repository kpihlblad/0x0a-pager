package raven.demo;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import raven.toast.Notifications;
import raven.toast.ui.ToastNotificationPanel;

import javax.swing.*;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Random;

public class Test extends JFrame {
    public Test() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 768);
        setLocationRelativeTo(null);
        getContentPane().setLayout(new FlowLayout(FlowLayout.LEADING));
        JButton button = new JButton("Show");
        Notifications.getInstance().setJFrame(this);
        CustomNotification customNotification = new CustomNotification();
        customNotification.setJFrame(this);
        button.addActionListener(e -> Notifications.getInstance().show(getRandomType(),
                Notifications.Location.TOP_RIGHT, getRandomText()));
        JButton cmdMode = new JButton("Mode Light");
        cmdMode.addActionListener(e -> {
            if (cmdMode.getText().equals("Mode Light")) {
                changeMode(true);
                cmdMode.setText("Mode Dark");
            } else {
                changeMode(false);
                cmdMode.setText("Mode Light");
            }
        });
        getContentPane().add(button);
        getContentPane().add(cmdMode);

        JButton buttonClear = new JButton("Clear");
        buttonClear.addActionListener(e -> Notifications.getInstance().clearAll());
        getContentPane().add(buttonClear);

        ToastNotificationPanel panel = new ToastNotificationPanel();
        panel.set(Notifications.Type.INFO, "Hello my name is raven\nThis new Toast Panel Notification");
        getContentPane().add(panel);
        testMemory();
    }

    private void testMemory() {
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                MemoryUsage memoryUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                System.out.println(formatSize(memoryUsage.getUsed()) + " of " + formatSize(memoryUsage.getCommitted()));
                sleep();
            }
        }).start();
    }

    private void sleep() {
        //noinspection CatchMayIgnoreException
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Restore the interrupt status so the memory-printing loop stops promptly.
            Thread.currentThread().interrupt();
        }
    }

    public String formatSize(long bytes) {
        int unit = 1024;
        if (bytes < unit) {
            return bytes + " B";
        }
        // Explicit cast: byte counts are far below 2^53, so the approximation is intentional.
        int exp = (int) (Math.log((double) bytes) / Math.log(unit));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    private String getRandomText() {
        Random ran = new Random();
        int a = ran.nextInt(5);
        if (a == 0) {
            return "Toast Notifications notify the user of a system occurrence";
        } else if (a == 1) {
            return "The notifications should have a consistent location in each application.\nWe recommend the top-right of the application";
        } else if (a == 2) {
            return """
                     Toast Notifications notify the user of a system occurrence.
                     The notifications should have a consistent location in each application.
                    We recommend the top-right""";
        } else if (a == 3) {
            return "Success";
        } else {
            return "Hello";
        }
    }

    private Notifications.Type getRandomType() {
        Random ran = new Random();
        int a = ran.nextInt(4);
        if (a == 0) {
            return Notifications.Type.SUCCESS;
        } else if (a == 1) {
            return Notifications.Type.INFO;
        } else if (a == 2) {
            return Notifications.Type.WARNING;
        } else {
            return Notifications.Type.ERROR;
        }
    }

    private void changeMode(boolean dark) {
        if (FlatLaf.isLafDark() != dark) {
            if (dark) {
                EventQueue.invokeLater(() -> {
                    FlatAnimatedLafChange.showSnapshot();
                    FlatMacDarkLaf.setup();
                    FlatLaf.updateUI();
                    FlatAnimatedLafChange.hideSnapshotWithAnimation();
                });
            } else {
                EventQueue.invokeLater(() -> {
                    FlatAnimatedLafChange.showSnapshot();
                    FlatMacLightLaf.setup();
                    FlatLaf.updateUI();
                    FlatAnimatedLafChange.hideSnapshotWithAnimation();
                });
            }
        }
    }

    public static void main(String[] args) {
        FlatLaf.registerCustomDefaultsSource("raven.toast");
        FlatMacLightLaf.setup();
        EventQueue.invokeLater(() -> new Test().setVisible(true));
    }
}
