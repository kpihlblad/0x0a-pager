package se.grynna.pager.ui;

import java.util.Locale;

/**
 * The key tradition a {@link KeyBinding} belongs to. Every binding stays live whatever the
 * preference; the flavour only decides which key menus, hints and tooltips show first
 * ("Key hints" preference). Universal keys (arrows, PgUp, Esc, ...) count as {@link #DESKTOP}.
 */
public enum KeyFlavour {
    DESKTOP("Desktop"), PAGER("Pager (less)"), EMACS("Emacs");

    private final String displayName;

    KeyFlavour(String displayName) {
        this.displayName = displayName;
    }

    /** The name shown in the preferences and in the help overlay. */
    public String displayName() {
        return displayName;
    }

    /** Parses a persisted name; anything unknown or blank gives {@link #DESKTOP}. */
    public static KeyFlavour fromName(String name) {
        if (name != null) {
            for (KeyFlavour flavour : values()) {
                if (flavour.name().equals(name.trim().toUpperCase(Locale.ROOT))) {
                    return flavour;
                }
            }
        }
        return DESKTOP;
    }
}
