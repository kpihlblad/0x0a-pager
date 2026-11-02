package se.grynna.pager.ui;

import java.util.Locale;

/**
 * Where the status bar and the prompt/action bar sit: stacked at the bottom of the window like {@code most}
 * ({@link #MOST}), or at the top with the status row merged into the custom window title bar and the prompt/action bar
 * directly below it ({@link #WIN}).
 */
public enum UiLayout {
    MOST("most (bottom bars)"), WIN("win (merged title bar)");

    private final String displayName;

    UiLayout(String displayName) {
        this.displayName = displayName;
    }

    /** The label shown in the preferences combo box. */
    public String displayName() {
        return displayName;
    }

    /** Parses a persisted name; anything unknown or blank gives {@link #MOST}. */
    public static UiLayout fromName(String name) {
        if (name != null) {
            for (UiLayout layout : values()) {
                if (layout.name().equals(name.trim().toUpperCase(Locale.ROOT))) {
                    return layout;
                }
            }
        }
        return MOST;
    }
}
