package se.grynna.pager.ui;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Decides what happens when the app is about to lose bookmarks that were never exported or copied:
 * on quitting and on logging out. With nothing unsaved the action just proceeds; otherwise the
 * {@link Chooser} is asked. The dialog is behind the {@code Chooser} so tests answer with a fake.
 * <p>
 * EDT only. Exporting is asynchronous: {@link Actions#exportBookmarks} receives the continuation and
 * runs it only after the file was written, so a cancelled or failed export keeps the app open.
 */
public final class UnsavedBookmarksGuard {

    /** What the app is about to do. */
    public enum Purpose {
        QUIT, LOG_OUT
    }

    /** The user's answer. */
    public enum Choice {
        /** Copy the bookmarks, then go on. */
        COPY,
        /** Open the Export dialog; go on only after a successful write. */
        EXPORT,
        /** Go on without saving. */
        PROCEED,
        /** Stay. */
        CANCEL
    }

    /** Asks the user; {@code unsaved} is at least one. */
    @FunctionalInterface
    public interface Chooser {
        Choice choose(Purpose purpose, int unsaved);
    }

    /** What the guard can do on the user's behalf. */
    public interface Actions {
        /** Copy all bookmarks to the clipboard. */
        void copyBookmarks();

        /** Open the Export dialog on the bookmarks; run {@code afterSuccessfulWrite} once the file is written. */
        void exportBookmarks(Runnable afterSuccessfulWrite);
    }

    private final IntSupplier unsaved;
    private final Chooser chooser;
    private final Actions actions;

    public UnsavedBookmarksGuard(IntSupplier unsaved, Chooser chooser, Actions actions) {
        this.unsaved = Objects.requireNonNull(unsaved, "unsaved");
        this.chooser = Objects.requireNonNull(chooser, "chooser");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** Run {@code proceed} now if nothing is unsaved, or as far as the user's answer allows. */
    public void guard(Purpose purpose, Runnable proceed) {
        int count = unsaved.getAsInt();
        if (count <= 0) {
            proceed.run();
            return;
        }
        Choice choice = chooser.choose(purpose, count);
        switch (choice == null ? Choice.CANCEL : choice) {
            case COPY -> {
                actions.copyBookmarks();
                proceed.run();
            }
            case EXPORT -> actions.exportBookmarks(proceed);
            case PROCEED -> proceed.run();
            case CANCEL -> {}
        }
    }

    /** Button labels in {@link Choice} order: copy, export, proceed, cancel. */
    public static String[] labels(Purpose purpose) {
        return purpose == Purpose.QUIT
                ? new String[]{"Copy and quit", "Export…", "Quit anyway", "Cancel"}
                : new String[]{"Copy and log out", "Export…", "Log out anyway", "Cancel"};
    }

    /** The production chooser: a modal option dialog parented to the component. */
    public static Chooser dialog(Supplier<Component> parent) {
        return (purpose, count) -> {
            String[] labels = labels(purpose);
            int picked = JOptionPane.showOptionDialog(parent.get(), BookmarkNotices.unsaved(count),
                    "Unsaved bookmarks", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, labels,
                    labels[0]);
            return switch (picked) {
                case 0 -> Choice.COPY;
                case 1 -> Choice.EXPORT;
                case 2 -> Choice.PROCEED;
                default -> Choice.CANCEL;
            };
        };
    }
}
