package se.grynna.pager.ui.clip;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The clipboard as the UI sees it. Production code uses {@link #system()}; tests put an in-memory
 * implementation behind the same two methods, so copy and paste can be exercised without the
 * system clipboard (which headless tests do not have).
 */
public interface ClipboardAccess {

    /** Plain text as the copier and the system clipboard advertise it. */
    DataFlavor PLAIN_TEXT = plainTextFlavor();

    /** The system clipboard. */
    static ClipboardAccess system() {
        return SystemClipboard.INSTANCE;
    }

    /** Replaces the clipboard contents. */
    void setContents(Transferable contents);

    /** The current clipboard contents, or {@code null} when there are none. */
    Transferable contents();

    /** Replaces the clipboard contents with plain {@code text}. */
    default void setText(String text) {
        setContents(new StringSelection(text));
    }

    /** The clipboard's plain text, if it offers any. */
    default Optional<String> text() {
        Transferable contents = contents();
        if (contents == null) {
            return Optional.empty();
        }
        for (DataFlavor flavor : List.of(DataFlavor.stringFlavor, PLAIN_TEXT)) {
            if (contents.isDataFlavorSupported(flavor)) {
                try {
                    return Optional.of(String.valueOf(contents.getTransferData(flavor)));
                } catch (UnsupportedFlavorException | IOException e) {
                    // Try the next flavour.
                }
            }
        }
        return Optional.empty();
    }

    private static DataFlavor plainTextFlavor() {
        try {
            return new DataFlavor("text/plain;class=java.lang.String");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("text/plain flavour unavailable", e);
        }
    }

    /**
     * The AWT system clipboard. Writes go through a platform thread, as the copier always did,
     * so a slow clipboard owner never stalls the event dispatch thread.
     */
    @Slf4j
    final class SystemClipboard implements ClipboardAccess {

        static final SystemClipboard INSTANCE = new SystemClipboard();

        private SystemClipboard() {}

        private static java.awt.datatransfer.Clipboard clipboard() {
            return Toolkit.getDefaultToolkit().getSystemClipboard();
        }

        @Override
        public void setContents(Transferable contents) {
            Thread.ofPlatform().start(() -> {
                try {
                    clipboard().setContents(contents, null);
                } catch (Exception e) {
                    log.error("Error copying to clipboard", e);
                }
            });
        }

        @Override
        public Transferable contents() {
            try {
                return clipboard().getContents(null);
            } catch (IllegalStateException e) {
                log.warn("Clipboard is busy: {}", e.getMessage());
                return null;
            }
        }
    }
}
