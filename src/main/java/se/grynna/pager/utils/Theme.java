package se.grynna.pager.utils;

/** The colour scheme of the application; {@link DarculaColors#use(Theme)} selects its palette. */
public enum Theme {
    DARK, LIGHT;

    /** The theme with the given name, or {@link #DARK} when the name is unknown or missing. */
    public static Theme fromName(String name) {
        for (Theme theme : values()) {
            if (theme.name().equalsIgnoreCase(name)) {
                return theme;
            }
        }
        return DARK;
    }
}
