package se.grynna.pager.ui;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Closing the main window: the frame does nothing by itself, and the close button asks the viewer
 * whether bookmarks would be lost before the app exits.
 */
public final class AppClose {

    private AppClose() {}

    /** The window listener for the main frame; {@code exit} ends the app once the viewer agrees. */
    public static WindowAdapter listener(LogViewer viewer, Runnable exit) {
        return new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                viewer.requestQuit(exit);
            }
        };
    }

    /** Make {@code frame} close through {@link #listener}. */
    public static void install(JFrame frame, LogViewer viewer, Runnable exit) {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(listener(viewer, exit));
    }
}
