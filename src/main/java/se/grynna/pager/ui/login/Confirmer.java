package se.grynna.pager.ui.login;

import javax.swing.*;
import java.awt.*;

/**
 * A yes/no question put to the user from inside an overlay, where the status bar (and its Y/N prompt)
 * is hidden. Injected so tests answer without showing a dialog.
 */
@FunctionalInterface
public interface Confirmer {

    /** Ask {@code message}; true when the user confirms. Called on the EDT. */
    boolean confirm(Component parent, String message);

    /** A modal {@link JOptionPane} confirm dialog parented to the component. */
    static Confirmer dialog() {
        return (parent, message) -> JOptionPane.showConfirmDialog(parent, message, "Confirm scope change",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }
}
