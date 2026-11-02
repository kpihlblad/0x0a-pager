package se.grynna.pager.splunk;

import java.time.ZoneId;
import java.util.Locale;
import se.grynna.pager.utils.Theme;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class Config {

    private final Preferences prefs;

    public Config() {
        this(Preferences.userNodeForPackage(Config.class));
    }

    Config(Preferences prefs) {
        this.prefs = prefs;
    }

    public String getSplunkHost() {
        return prefs.get("splunkHost", "");
    }

    public String getSplunkUser() {
        return prefs.get("splunkUser", defaultUserName());
    }

    public int getSplunkPort() {
        return prefs.getInt("splunkPort", getDefaultPort());
    }

    public void setSplunkHost(String splunkHost) {
        prefs.put("splunkHost", splunkHost);
    }

    public void setSplunkUser(String splunkUser) {
        prefs.put("splunkUser", splunkUser);
    }

    public void setSplunkPort(int splunkPort) {
        prefs.putInt("splunkPort", splunkPort);
    }

    public int getDefaultPort() {
        return 8089;
    }

    public ZoneId getZoneId() {
        String zoneIdStr = prefs.get("zoneId", ZoneId.systemDefault().getId());
        return ZoneId.of(zoneIdStr);
    }

    public Locale getLocale() {
        String localeStr = prefs.get("locale", Locale.getDefault().toLanguageTag());
        return Locale.forLanguageTag(localeStr);
    }

    public void setZoneId(ZoneId zoneId) {
        prefs.put("zoneId", zoneId.getId());
    }

    public void setLocale(Locale locale) {
        prefs.put("locale", locale.toLanguageTag());
    }

    /** Matches view: show each event as its first line only (default: whole events). */
    public boolean getMatchesFirstLineOnly() {
        return prefs.getBoolean("matchesFirstLineOnly", false);
    }

    public void setMatchesFirstLineOnly(boolean firstLineOnly) {
        prefs.putBoolean("matchesFirstLineOnly", firstLineOnly);
    }

    /** Search minimap: the thin match-density strip at the right edge of the log (default: shown). */
    public boolean getShowMinimap() {
        return prefs.getBoolean("showMinimap", true);
    }

    public void setShowMinimap(boolean showMinimap) {
        prefs.putBoolean("showMinimap", showMinimap);
    }

    /**
     * "Key hints" preference: which key tradition menus and hints show first, as a
     * {@code KeyFlavour} name ({@code DESKTOP}, {@code PAGER} or {@code EMACS}); every key works whatever it is.
     */
    public String getKeyHints() {
        return prefs.get("keyHints", "DESKTOP");
    }

    public void setKeyHints(String keyHints) {
        prefs.put("keyHints", keyHints);
    }

    /** The colour scheme chosen in the main menu; {@link Theme#DARK} until the user picks the light one. */
    public Theme getTheme() {
        return Theme.fromName(prefs.get("theme", Theme.DARK.name()));
    }

    public void setTheme(Theme theme) {
        prefs.put("theme", theme.name());
    }

    public void flush() throws BackingStoreException {
        prefs.flush();
    }

    public String defaultUserName() {
        // figure out the logged in user
        String userName = System.getenv("USER");
        if (userName == null) {
            userName = System.getProperty("user.name");
        }
        return userName;
    }
}
