package se.grynna.pager.ui;

import se.grynna.pager.ui.KeyBinding.Context;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static se.grynna.pager.ui.KeyFlavour.EMACS;
import static se.grynna.pager.ui.KeyFlavour.PAGER;

/**
 * The single key map: every {@link UiAction} with one canonical key plus any
 * number of aliases, each binding carrying its guard class and context.
 * <p>
 * The aliases deliberately cover three traditions so that pager, desktop and
 * Emacs users all find their keys. Ctrl-G stays
 * {@link UiAction#CANCEL}; desktop users who expect "find next" have F3 and
 * {@code n}.
 * <p>
 * Every binding carries a {@link KeyFlavour}. All of them are always live; the flavour only
 * decides which key {@link #label(UiAction)} shows (the preferred flavour's, falling back to the
 * desktop key and then the canonical one). Instances are immutable;
 * {@link #defaults()} returns the shared table with desktop labels and {@link #preferred()} the
 * same table labelled for the "Key hints" preference.
 */
public final class KeyMap {

    private static final KeyMap DEFAULTS = buildDefaults();

    private static volatile KeyFlavour preferredFlavour = KeyFlavour.DESKTOP;

    private final Map<UiAction, List<KeyBinding>> bindings;
    private final Map<KeyBinding, UiAction> actions;
    private final KeyFlavour flavour;

    private KeyMap(KeyMap shared, KeyFlavour flavour) {
        this.bindings = shared.bindings;
        this.actions = shared.actions;
        this.flavour = flavour;
    }

    private KeyMap(Map<UiAction, List<KeyBinding>> bindings) {
        this.flavour = KeyFlavour.DESKTOP;
        EnumMap<UiAction, List<KeyBinding>> frozen = new EnumMap<>(UiAction.class);
        Map<KeyBinding, UiAction> reverse = new LinkedHashMap<>();
        bindings.forEach((action, list) -> {
            List<KeyBinding> copy = List.copyOf(list);
            frozen.put(action, copy);
            for (KeyBinding binding : copy) {
                UiAction previous = reverse.putIfAbsent(binding, action);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate binding " + binding + ": " + previous + " and " + action);
                }
            }
        });
        this.bindings = Collections.unmodifiableMap(frozen);
        this.actions = Collections.unmodifiableMap(reverse);
    }

    /** The shared default table, with desktop labels. */
    public static KeyMap defaults() {
        return DEFAULTS;
    }

    /** The shared table labelled for the "Key hints" preference. */
    public static KeyMap preferred() {
        return DEFAULTS.withFlavour(preferredFlavour);
    }

    /** Sets the flavour {@link #preferred()} labels with (the "Key hints" preference). */
    public static void setPreferredFlavour(KeyFlavour flavour) {
        preferredFlavour = flavour == null ? KeyFlavour.DESKTOP : flavour;
    }

    /** The flavour of the "Key hints" preference. */
    public static KeyFlavour preferredFlavour() {
        return preferredFlavour;
    }

    /** The same bindings, with {@link #label(UiAction)} preferring {@code flavour}'s keys. */
    public KeyMap withFlavour(KeyFlavour flavour) {
        return flavour == this.flavour ? this : new KeyMap(this, flavour);
    }

    /** The flavour this map's labels prefer. */
    public KeyFlavour flavour() {
        return flavour;
    }

    /** The canonical key shown in help texts and menus. */
    public KeyBinding canonical(UiAction action) {
        return all(action).get(0);
    }

    /** All alias bindings for {@code action}, canonical excluded. */
    public List<KeyBinding> aliases(UiAction action) {
        List<KeyBinding> all = all(action);
        return all.size() <= 1 ? List.of() : List.copyOf(all.subList(1, all.size()));
    }

    /** Every binding for {@code action}, canonical first. */
    public List<KeyBinding> all(UiAction action) {
        List<KeyBinding> found = bindings.get(action);
        if (found == null) {
            throw new IllegalArgumentException("No key binding for " + action);
        }
        return found;
    }

    /**
     * Every binding live in {@code context}, including the {@link Context#ANY}
     * ones. {@link Context#PROMPT} yields the prompt answers plus the always-on
     * keys (Esc, cursor keys, ...); {@link Context#LOG_VIEW} does not include the
     * PROMPT-only answers.
     */
    public List<KeyBinding> bindingsFor(Context context) {
        List<KeyBinding> result = new ArrayList<>();
        for (UiAction action : UiAction.values()) {
            for (KeyBinding binding : all(action)) {
                if (binding.appliesTo(context)) {
                    result.add(binding);
                }
            }
        }
        return List.copyOf(result);
    }

    /** The action a concrete binding triggers, if it belongs to this map. */
    public Optional<UiAction> actionFor(KeyBinding binding) {
        return Optional.ofNullable(actions.get(binding));
    }

    /**
     * Human label of the key shown for {@code action}, for example {@code "F3"} or {@code "Ctrl-F"}:
     * the first binding of this map's flavour, else the first desktop one, else the canonical key.
     */
    public String label(UiAction action) {
        return label(action, flavour);
    }

    /**
     * Like {@link #label(UiAction)}, but only among the keys live in {@code context}: the same preference
     * order (this map's flavour, then desktop, then the first), for actions whose keys differ per view.
     */
    public String label(UiAction action, Context context) {
        List<KeyBinding> live = all(action).stream().filter(binding -> binding.appliesTo(context)).toList();
        if (live.isEmpty()) {
            return label(action);
        }
        for (KeyFlavour candidate : new KeyFlavour[]{flavour, KeyFlavour.DESKTOP}) {
            for (KeyBinding binding : live) {
                if (binding.flavour() == candidate) {
                    return format(binding.stroke());
                }
            }
        }
        return format(live.get(0).stroke());
    }

    /** Like {@link #label(UiAction)} for an explicit flavour. */
    public String label(UiAction action, KeyFlavour preferred) {
        return format(shown(action, preferred).stroke());
    }

    private KeyBinding shown(UiAction action, KeyFlavour preferred) {
        List<KeyBinding> all = all(action);
        for (KeyFlavour candidate : new KeyFlavour[]{preferred, KeyFlavour.DESKTOP}) {
            for (KeyBinding binding : all) {
                if (binding.flavour() == candidate) {
                    return binding;
                }
            }
        }
        return all.get(0);
    }

    /** Whether {@code action} has a key of exactly this flavour (no fallback). */
    public boolean hasFlavour(UiAction action, KeyFlavour flavour) {
        return all(action).stream().anyMatch(binding -> binding.flavour() == flavour);
    }

    /** Every key of exactly {@code flavour} for {@code action}, joined, for example {@code "F3"}; empty if none. */
    public String labels(UiAction action, KeyFlavour flavour) {
        return all(action).stream().filter(binding -> binding.flavour() == flavour)
                .map(binding -> format(binding.stroke())).distinct().collect(Collectors.joining(" / "));
    }

    /** Canonical label plus aliases, for example {@code "F3 / n / Ctrl-S"}. */
    public String labelWithAliases(UiAction action) {
        return all(action).stream().map(binding -> format(binding.stroke())).distinct()
                .collect(Collectors.joining(" / "));
    }

    /**
     * Formats a keystroke without {@link KeyEvent#getKeyText(int)}: that call is
     * locale dependent while the help overlay is not.
     */
    static String format(KeyStroke stroke) {
        return modifiers(stroke.getModifiers()) + keyText(stroke);
    }

    private static String modifiers(int mask) {
        StringBuilder text = new StringBuilder();
        if ((mask & InputEvent.CTRL_DOWN_MASK) != 0) {
            text.append("Ctrl-");
        }
        if ((mask & InputEvent.ALT_DOWN_MASK) != 0) {
            text.append("Alt-");
        }
        if ((mask & InputEvent.SHIFT_DOWN_MASK) != 0) {
            text.append("Shift-");
        }
        if ((mask & InputEvent.META_DOWN_MASK) != 0) {
            text.append("Meta-");
        }
        return text.toString();
    }

    private static String keyText(KeyStroke stroke) {
        if (stroke.getKeyEventType() == KeyEvent.KEY_TYPED) {
            return switch (stroke.getKeyChar()) {
                case ' ' -> "Space";
                case '\n' -> "Enter";
                case '\t' -> "Tab";
                case '\b' -> "Backspace";
                case KeyEvent.CHAR_UNDEFINED -> "?";
                default -> String.valueOf(stroke.getKeyChar());
            };
        }
        int code = stroke.getKeyCode();
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) {
            return "F" + (code - KeyEvent.VK_F1 + 1);
        }
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z || code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
            return String.valueOf((char) code);
        }
        return switch (code) {
            case KeyEvent.VK_UP -> "Up";
            case KeyEvent.VK_DOWN -> "Down";
            case KeyEvent.VK_LEFT -> "Left";
            case KeyEvent.VK_RIGHT -> "Right";
            case KeyEvent.VK_PAGE_UP -> "PgUp";
            case KeyEvent.VK_PAGE_DOWN -> "PgDn";
            case KeyEvent.VK_SCROLL_LOCK -> "ScrLk";
            case KeyEvent.VK_HOME -> "Home";
            case KeyEvent.VK_END -> "End";
            case KeyEvent.VK_ENTER -> "Enter";
            case KeyEvent.VK_SPACE -> "Space";
            case KeyEvent.VK_TAB -> "Tab";
            case KeyEvent.VK_ESCAPE -> "Esc";
            case KeyEvent.VK_BACK_SPACE -> "Backspace";
            case KeyEvent.VK_INSERT -> "Ins";
            case KeyEvent.VK_DELETE -> "Del";
            case KeyEvent.VK_CONTEXT_MENU -> "Menu";
            default -> "Key" + code;
        };
    }

    private static KeyMap buildDefaults() {
        Builder builder = new Builder();

        // Debug repaint.
        builder.add(UiAction.REPAINT, key(KeyEvent.VK_F1, 0, KeyGuard.UNGUARDED, Context.ANY));

        // Cursor movement — guarded so arrows still work inside text fields.
        builder.add(UiAction.CURSOR_UP, key(KeyEvent.VK_UP, 0, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.CURSOR_DOWN, key(KeyEvent.VK_DOWN, 0, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.CURSOR_LEFT, key(KeyEvent.VK_LEFT, 0, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.CURSOR_RIGHT, key(KeyEvent.VK_RIGHT, 0, KeyGuard.GUARDED, Context.ANY));

        builder.add(UiAction.SELECT_UP, key(KeyEvent.VK_UP, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.SELECT_DOWN,
                key(KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.SELECT_LEFT,
                key(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.SELECT_RIGHT,
                key(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));

        builder.add(UiAction.CURSOR_UP_FAST,
                key(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.CURSOR_DOWN_FAST,
                key(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.CURSOR_LEFT_FAST,
                key(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.CURSOR_RIGHT_FAST,
                key(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));

        int ctrlShift = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
        builder.add(UiAction.SELECT_UP_FAST, key(KeyEvent.VK_UP, ctrlShift, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.SELECT_DOWN_FAST, key(KeyEvent.VK_DOWN, ctrlShift, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.SELECT_LEFT_FAST, key(KeyEvent.VK_LEFT, ctrlShift, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.SELECT_RIGHT_FAST, key(KeyEvent.VK_RIGHT, ctrlShift, KeyGuard.GUARDED, Context.ANY));

        // Paging — unguarded (text fields do not use PgUp/PgDn), pager aliases guarded.
        builder.add(UiAction.PAGE_UP, key(KeyEvent.VK_PAGE_UP, 0, KeyGuard.UNGUARDED, Context.ANY));
        builder.add(UiAction.PAGE_UP, typed('b', KeyGuard.GUARDED, Context.ANY).tagged(PAGER));
        builder.add(UiAction.PAGE_DOWN, key(KeyEvent.VK_PAGE_DOWN, 0, KeyGuard.UNGUARDED, Context.ANY));
        builder.add(UiAction.PAGE_DOWN, key(KeyEvent.VK_SPACE, 0, KeyGuard.GUARDED, Context.ANY).tagged(PAGER));

        builder.add(UiAction.SELECT_PAGE_UP,
                key(KeyEvent.VK_PAGE_UP, InputEvent.SHIFT_DOWN_MASK, KeyGuard.UNGUARDED, Context.ANY));
        builder.add(UiAction.SELECT_PAGE_DOWN,
                key(KeyEvent.VK_PAGE_DOWN, InputEvent.SHIFT_DOWN_MASK, KeyGuard.UNGUARDED, Context.ANY));

        builder.add(UiAction.SET_MARK,
                key(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.ANY).tagged(EMACS));
        builder.add(UiAction.COPY, key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.ANY));
        // Copy as…: opens the format menu. Bound per view, not for the prompt, whose text field has its own keys.
        builder.add(UiAction.COPY_AS, key(KeyEvent.VK_C, ctrlShift, KeyGuard.GUARDED, Context.LOG_VIEW));
        builder.add(UiAction.COPY_AS, key(KeyEvent.VK_C, ctrlShift, KeyGuard.GUARDED, Context.MATCHES_VIEW));
        builder.add(UiAction.COPY_AS, key(KeyEvent.VK_C, ctrlShift, KeyGuard.GUARDED, Context.BOOKMARKS_VIEW));

        // Log view keeps Enter for details; the Matches view uses it to show in log.
        builder.add(UiAction.OPEN_DETAILS, key(KeyEvent.VK_ENTER, 0, KeyGuard.GUARDED, Context.LOG_VIEW));

        // Cursor mode: F7 is the caret-browsing key of Firefox, Chrome, Edge and Windows; `v` the pager
        // alias; Scroll Lock the classic page-mode key (guarded, so it never fires while typing).
        builder.add(UiAction.TOGGLE_CURSOR, key(KeyEvent.VK_F7, 0, KeyGuard.UNGUARDED, Context.ANY));
        builder.add(UiAction.TOGGLE_CURSOR, typed('v', KeyGuard.GUARDED, Context.ANY).tagged(PAGER));
        builder.add(UiAction.TOGGLE_CURSOR, key(KeyEvent.VK_SCROLL_LOCK, 0, KeyGuard.GUARDED, Context.ANY));

        // Login and the time panel (G: go to a time, R: set the range) are bare letters, guarded.
        builder.add(UiAction.LOGIN, key(KeyEvent.VK_L, 0, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.GOTO, key(KeyEvent.VK_G, 0, KeyGuard.GUARDED, Context.ANY));
        builder.add(UiAction.SET_RANGE, key(KeyEvent.VK_R, 0, KeyGuard.GUARDED, Context.ANY));

        // Cancel chain — unguarded, must work while typing.
        builder.add(UiAction.CANCEL, key(KeyEvent.VK_ESCAPE, 0, KeyGuard.UNGUARDED, Context.ANY));
        builder.add(UiAction.CANCEL,
                key(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK, KeyGuard.UNGUARDED, Context.ANY).tagged(EMACS));

        // Search prompt: Ctrl-F canonical, `/` the pager alias. These keys always open the
        // prompt (pre-filled with the active term, all selected) and never mean "next". F3/Ctrl-S
        // opening the prompt when no search is active stays runtime logic in
        // LogViewer.searchNext, so those keys are not duplicated here. Ctrl-F also fires in the
        // search input (to re-select the term), but not in other text fields.
        builder.add(UiAction.OPEN_SEARCH,
                key(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK, KeyGuard.SEARCH_INPUT_OK, Context.ANY));
        builder.add(UiAction.OPEN_SEARCH, typed('/', KeyGuard.GUARDED, Context.ANY).tagged(PAGER));

        builder.add(UiAction.OPEN_SEARCH_BACKWARD, typed('?', KeyGuard.GUARDED, Context.ANY).tagged(PAGER));

        // Hit navigation: F3 canonical, pager/Emacs aliases. `N` lives in the
        // views only, so the prompt's "no" answer can win while a prompt shows.
        builder.add(UiAction.NEXT_MATCH, key(KeyEvent.VK_F3, 0, KeyGuard.UNGUARDED, Context.ANY));
        builder.add(UiAction.NEXT_MATCH, typed('n', KeyGuard.GUARDED, Context.LOG_VIEW).tagged(PAGER));
        builder.add(UiAction.NEXT_MATCH, typed('n', KeyGuard.GUARDED, Context.MATCHES_VIEW).tagged(PAGER));
        builder.add(UiAction.NEXT_MATCH,
                key(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK, KeyGuard.UNGUARDED, Context.ANY).tagged(EMACS));

        builder.add(UiAction.PREVIOUS_MATCH, key(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK, KeyGuard.UNGUARDED,
                Context.ANY));
        builder.add(UiAction.PREVIOUS_MATCH, typed('N', KeyGuard.GUARDED, Context.LOG_VIEW).tagged(PAGER));
        builder.add(UiAction.PREVIOUS_MATCH, typed('N', KeyGuard.GUARDED, Context.MATCHES_VIEW).tagged(PAGER));
        builder.add(UiAction.PREVIOUS_MATCH,
                key(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK, KeyGuard.UNGUARDED, Context.ANY).tagged(EMACS));
        builder.add(UiAction.PREVIOUS_MATCH, key(KeyEvent.VK_F3, ctrlShift, KeyGuard.UNGUARDED, Context.ANY));

        builder.add(UiAction.SEARCH_SELECTION_FORWARD, typed('*', KeyGuard.GUARDED, Context.ANY).tagged(PAGER));
        builder.add(UiAction.SEARCH_SELECTION_BACKWARD, typed('#', KeyGuard.GUARDED, Context.ANY).tagged(PAGER));

        // Matches view. F4/& toggle into the view from the log and back out (as
        // SHOW_IN_LOG) from the view, so the same key does the obvious thing in
        // both contexts.
        builder.add(UiAction.TOGGLE_MATCHES, key(KeyEvent.VK_F4, 0, KeyGuard.UNGUARDED, Context.LOG_VIEW));
        builder.add(UiAction.TOGGLE_MATCHES, typed('&', KeyGuard.GUARDED, Context.LOG_VIEW).tagged(PAGER));

        builder.add(UiAction.SHOW_IN_LOG, key(KeyEvent.VK_ENTER, 0, KeyGuard.GUARDED, Context.MATCHES_VIEW));
        builder.add(UiAction.SHOW_IN_LOG, key(KeyEvent.VK_F4, 0, KeyGuard.UNGUARDED, Context.MATCHES_VIEW));
        builder.add(UiAction.SHOW_IN_LOG, typed('&', KeyGuard.GUARDED, Context.MATCHES_VIEW).tagged(PAGER));

        builder.add(UiAction.MATCH_DETAILS,
                key(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.MATCHES_VIEW));
        builder.add(UiAction.MATCH_DETAILS, typed('i', KeyGuard.GUARDED, Context.MATCHES_VIEW).tagged(PAGER));

        // Scope.
        builder.add(UiAction.NARROW, typed('+', KeyGuard.GUARDED, Context.LOG_VIEW));
        builder.add(UiAction.NARROW, typed('+', KeyGuard.GUARDED, Context.MATCHES_VIEW));
        builder.add(UiAction.EXCLUDE, typed('-', KeyGuard.GUARDED, Context.LOG_VIEW));
        builder.add(UiAction.EXCLUDE, typed('-', KeyGuard.GUARDED, Context.MATCHES_VIEW));

        // Bookmarks: `less`-style marks. `m` toggles, `M` opens the view; both
        // are bare letters, so they live in the views and stay out of prompts.
        builder.add(UiAction.TOGGLE_BOOKMARK, typed('m', KeyGuard.GUARDED, Context.LOG_VIEW).tagged(PAGER));
        builder.add(UiAction.TOGGLE_BOOKMARK, typed('m', KeyGuard.GUARDED, Context.MATCHES_VIEW).tagged(PAGER));
        builder.add(UiAction.TOGGLE_BOOKMARK,
                key(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.LOG_VIEW));
        builder.add(UiAction.TOGGLE_BOOKMARK,
                key(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.MATCHES_VIEW));
        builder.add(UiAction.BOOKMARKS_VIEW, typed('M', KeyGuard.GUARDED, Context.LOG_VIEW).tagged(PAGER));
        builder.add(UiAction.BOOKMARKS_VIEW, typed('M', KeyGuard.GUARDED, Context.MATCHES_VIEW).tagged(PAGER));
        // F2 toggles like F4 does for the matches: the bookmark key of Notepad++, Sublime and VS Code.
        builder.add(UiAction.BOOKMARKS_VIEW, key(KeyEvent.VK_F2, 0, KeyGuard.UNGUARDED, Context.LOG_VIEW));
        builder.add(UiAction.BOOKMARKS_VIEW, key(KeyEvent.VK_F2, 0, KeyGuard.UNGUARDED, Context.MATCHES_VIEW));
        builder.add(UiAction.BOOKMARKS_VIEW, key(KeyEvent.VK_F2, 0, KeyGuard.UNGUARDED, Context.BOOKMARKS_VIEW));
        builder.add(UiAction.BOOKMARKS_VIEW, key(KeyEvent.VK_B, ctrlShift, KeyGuard.GUARDED, Context.LOG_VIEW));
        builder.add(UiAction.BOOKMARKS_VIEW, key(KeyEvent.VK_B, ctrlShift, KeyGuard.GUARDED, Context.MATCHES_VIEW));

        // Inside the Bookmarks view: `m` removes, Enter shows the event in the log, F4/& go to the Matches
        // view (Esc, the way back, is the cancel chain). Details work as in the Matches view.
        builder.add(UiAction.TOGGLE_BOOKMARK, typed('m', KeyGuard.GUARDED, Context.BOOKMARKS_VIEW).tagged(PAGER));
        builder.add(UiAction.TOGGLE_BOOKMARK,
                key(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.BOOKMARKS_VIEW));
        builder.add(UiAction.SHOW_IN_LOG, key(KeyEvent.VK_ENTER, 0, KeyGuard.GUARDED, Context.BOOKMARKS_VIEW));
        builder.add(UiAction.TOGGLE_MATCHES, key(KeyEvent.VK_F4, 0, KeyGuard.UNGUARDED, Context.BOOKMARKS_VIEW));
        builder.add(UiAction.TOGGLE_MATCHES, typed('&', KeyGuard.GUARDED, Context.BOOKMARKS_VIEW).tagged(PAGER));
        builder.add(UiAction.MATCH_DETAILS,
                key(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.BOOKMARKS_VIEW));
        builder.add(UiAction.MATCH_DETAILS, typed('i', KeyGuard.GUARDED, Context.BOOKMARKS_VIEW).tagged(PAGER));

        // Narrow to the row's activity: bare typed keys in all three
        // views, none in prompts. The row menu opens with Shift-F10 or the context-menu key; guarded, so a text
        // field keeps its own.
        for (Context view : new Context[]{Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW}) {
            builder.add(UiAction.NARROW_TO_ACTIVITY, typed('a', KeyGuard.GUARDED, view));
            builder.add(UiAction.NARROW_TO_REQUEST, typed('A', KeyGuard.GUARDED, view));
            builder.add(UiAction.CONTEXT_MENU,
                    key(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, view));
            builder.add(UiAction.CONTEXT_MENU, key(KeyEvent.VK_CONTEXT_MENU, 0, KeyGuard.GUARDED, view));
        }

        builder.add(UiAction.HELP, typed('h', KeyGuard.GUARDED, Context.ANY).tagged(PAGER));

        // Event details: live while the details are open over a view. The view
        // keys do not fire then; the arrows, paging, Ctrl-C, Esc and `h` of ANY keep working (they drive the
        // panel). F3 and Shift-F3 shadow the ANY match keys: they step to the next event instead.
        builder.add(UiAction.DETAILS_PREVIOUS,
                key(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_PREVIOUS,
                key(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK, KeyGuard.UNGUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_PREVIOUS, typed('N', KeyGuard.GUARDED, Context.DETAILS_VIEW).tagged(PAGER));
        builder.add(UiAction.DETAILS_NEXT,
                key(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_NEXT, key(KeyEvent.VK_F3, 0, KeyGuard.UNGUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_NEXT, typed('n', KeyGuard.GUARDED, Context.DETAILS_VIEW).tagged(PAGER));
        builder.add(UiAction.DETAILS_FIRST, key(KeyEvent.VK_HOME, 0, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_LAST, key(KeyEvent.VK_END, 0, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_NARROW_FIELD, typed('+', KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_EXCLUDE_FIELD, typed('-', KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.DETAILS_MESSAGE_FIELD, typed('f', KeyGuard.GUARDED, Context.DETAILS_VIEW));
        // Enter, Shift-Enter and `i` open the details, so pressed again they close them.
        builder.add(UiAction.OPEN_DETAILS, key(KeyEvent.VK_ENTER, 0, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.MATCH_DETAILS,
                key(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.MATCH_DETAILS, typed('i', KeyGuard.GUARDED, Context.DETAILS_VIEW).tagged(PAGER));
        builder.add(UiAction.TOGGLE_BOOKMARK, typed('m', KeyGuard.GUARDED, Context.DETAILS_VIEW).tagged(PAGER));
        builder.add(UiAction.TOGGLE_BOOKMARK,
                key(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.CONTEXT_MENU,
                key(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK, KeyGuard.GUARDED, Context.DETAILS_VIEW));
        builder.add(UiAction.CONTEXT_MENU, key(KeyEvent.VK_CONTEXT_MENU, 0, KeyGuard.GUARDED, Context.DETAILS_VIEW));

        // Y/N prompt answers win while the prompt shows; enter and space are the
        // roadmap's "also accept return and space as yes". Letters are handled by
        // their KEY_TYPED events so that Shift-N cannot slip through as a
        // KEY_PRESSED that the view bindings would also see.
        builder.add(UiAction.PROMPT_YES, typed('y', KeyGuard.GUARDED, Context.PROMPT));
        builder.add(UiAction.PROMPT_YES, typed('Y', KeyGuard.GUARDED, Context.PROMPT));
        builder.add(UiAction.PROMPT_YES, key(KeyEvent.VK_SPACE, 0, KeyGuard.GUARDED, Context.PROMPT));
        builder.add(UiAction.PROMPT_YES, key(KeyEvent.VK_ENTER, 0, KeyGuard.GUARDED, Context.PROMPT));
        builder.add(UiAction.PROMPT_NO, typed('n', KeyGuard.GUARDED, Context.PROMPT));
        builder.add(UiAction.PROMPT_NO, typed('N', KeyGuard.GUARDED, Context.PROMPT));

        return new KeyMap(builder.build());
    }

    private static KeyBinding key(int keyCode, int modifiers, KeyGuard guard, Context context) {
        return new KeyBinding(KeyStroke.getKeyStroke(keyCode, modifiers), guard, context);
    }

    /** Printable characters are bound on KEY_TYPED so they survive any layout. */
    private static KeyBinding typed(char keyChar, KeyGuard guard, Context context) {
        return new KeyBinding(KeyStroke.getKeyStroke(Character.valueOf(keyChar), 0), guard, context);
    }

    private static final class Builder {
        private final Map<UiAction, List<KeyBinding>> byAction = new EnumMap<>(UiAction.class);
        private final Set<KeyBinding> seen = new LinkedHashSet<>();

        void add(UiAction action, KeyBinding binding) {
            if (!seen.add(binding)) {
                throw new IllegalArgumentException("Duplicate binding " + binding + " for " + action);
            }
            byAction.computeIfAbsent(action, key -> new ArrayList<>()).add(binding);
        }

        Map<UiAction, List<KeyBinding>> build() {
            return byAction;
        }
    }
}
