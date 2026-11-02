package se.grynna.pager.ui;

import java.util.Locale;

/**
 * Input state management - defines behavior of input bar.
 * <p>
 * Prompts name keys through {@code {ACTION}} / {@code {ACTION_ALL}} tokens that
 * are resolved against a {@link KeyMap} (canonical label vs canonical + aliases;
 * {@code {ACTION:CONTEXT}} the key bound in one view);
 * {@link #yesNo(KeyMap)} gives the uppercase Y/N shown after the suggested time. Any {@code %s} placeholders
 * are filled by the caller's arguments after the tokens are replaced.
 */
public enum InputState {
    // @formatter:off
    DEFAULT(false, ""),
    INIT(false, "Press {LOGIN} to login and select index."),
    INIT_CURSOR(false, "Cursor mode, {TOGGLE_CURSOR} for page mode."),
    HELP(false, "Keyboard help · {LOGIN} scope · {TOGGLE_CURSOR} cursor mode · {HELP} or {CANCEL} back"),
    HELP_CURSOR(false, "Keyboard help (cursor mode) · {TOGGLE_CURSOR} page mode · {HELP} or {CANCEL} back"),
    MODAL_LOGIN(false, "Enter Splunk credentials to login."),
    MODAL_INDEX(false, "Choose the scope: index and field filters"),
    SCROLL(false, "{NEXT_MATCH} next match, {OPEN_SEARCH} to search, {GOTO} to go to time, {TOGGLE_CURSOR} cursor mode, {HELP} for help."),
    CURSOR(false, "Cursor mode, {TOGGLE_CURSOR} for page mode, {OPEN_DETAILS} to inspect, {HELP} for help."),
    SELECTING(false, "Selecting, {COPY} to copy. Press {CANCEL} to clear the selection."),
    SEARCH_INPUT(true, "Search:"),
    SEARCH_ACTIVE(false, "%s · {NEXT_MATCH} next · {TOGGLE_MATCHES} show matches · {CANCEL} end"),
    MATCHES(false, "Matches: %s · {SHOW_IN_LOG} show in log · {CANCEL} back · {NEXT_MATCH} next match"),
    BOOKMARKS(false, "%s · {SHOW_IN_LOG:BOOKMARKS_VIEW} show in log · {TOGGLE_BOOKMARK:BOOKMARKS_VIEW} remove · {CANCEL} back"),
    DETAILS(false, "Details · ↑↓ field · {DETAILS_PREVIOUS}/{DETAILS_NEXT} prev/next · {DETAILS_NARROW_FIELD} narrow · {DETAILS_EXCLUDE_FIELD} exclude · {DETAILS_MESSAGE_FIELD} message · {CANCEL} back"),
    SEARCH_EXTEND(false, "No %s matches · search"),
    SEARCH_EXTEND_NONE(false, "No matches for \"%s\" · search"),
    SEARCH_RECENTRE(false, "Search around:"),
    SCOPE_CONFIRM(false, "%s"),
    BOUNDARY_EXPANSION(false, "No more logs · load");
    // @formatter:on

    final String template;
    final boolean editable;

    InputState(boolean editable, String template) {
        this.template = template;
        this.editable = editable;
    }

    /**
     * Whether the input bar is waiting for an answer in this state: a typed one (search) or a Y/N
     * question. Entering such a state flashes the bar to draw the eye to it.
     */
    public boolean needsInput() {
        return switch (this) {
            case SEARCH_INPUT, BOUNDARY_EXPANSION, SEARCH_EXTEND, SEARCH_EXTEND_NONE, SEARCH_RECENTRE,
                    SCOPE_CONFIRM ->
                true;
            default -> false;
        };
    }

    /**
     * Renders this state's prompt with the preferred key map, then fills any {@code %s} placeholders.
     */
    public String text(String... args) {
        return text(KeyMap.preferred(), args);
    }

    /**
     * The key hint shown after the input field while this state's prompt is open, or an empty
     * string when the state has none: {@code Enter search · F3/Shift-F3 next/prev}.
     */
    public String hint(KeyMap keyMap) {
        String template = switch (this) {
            case SEARCH_INPUT -> "Enter search · Shift-Enter show matches · {NEXT_MATCH}/{PREVIOUS_MATCH} next/prev";
            default -> "";
        };
        return renderTemplate(template, keyMap);
    }

    /**
     * Renders this state's prompt with {@code keyMap}, then fills any {@code %s} placeholders.
     */
    public String text(KeyMap keyMap, String... args) {
        String rendered = renderTemplate(template, keyMap);
        return args.length == 0 ? rendered : rendered.formatted((Object[]) args);
    }

    /**
     * The uppercase Y/N answer labels, e.g. {@code "Y/N"}, for prompt displays.
     */
    public static String yesNo(KeyMap keyMap) {
        return keyMap.label(UiAction.PROMPT_YES).toUpperCase(Locale.ROOT) + "/"
                + keyMap.label(UiAction.PROMPT_NO).toUpperCase(Locale.ROOT);
    }

    private static String renderTemplate(String template, KeyMap keyMap) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int end = template.indexOf('}', i + 1);
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            out.append(renderToken(template.substring(i + 1, end), keyMap));
            i = end + 1;
        }
        return out.toString();
    }

    private static String renderToken(String token, KeyMap keyMap) {
        if ("YES_NO".equals(token)) {
            return yesNo(keyMap);
        }
        int colon = token.indexOf(':');
        if (colon > 0) {
            // ACTION:CONTEXT names the key as it is bound in that view.
            return keyMap.label(UiAction.valueOf(token.substring(0, colon)),
                    KeyBinding.Context.valueOf(token.substring(colon + 1)));
        }
        boolean withAliases = token.endsWith("_ALL");
        String actionName = withAliases ? token.substring(0, token.length() - "_ALL".length()) : token;
        UiAction action = UiAction.valueOf(actionName);
        return withAliases ? keyMap.labelWithAliases(action) : keyMap.label(action);
    }
}
