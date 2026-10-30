package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.KeyBinding.Context;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KeyMapTest {

    private final KeyMap map = KeyMap.defaults();

    @AfterEach
    void restorePreferredFlavour() {
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
    }

    @Test
    void everyActionHasACanonicalBinding() {
        for (UiAction action : UiAction.values()) {
            assertThat(map.canonical(action)).as("canonical binding of %s", action).isNotNull();
        }
    }

    @Test
    void canonicalIsFirstAndAliasesFollow() {
        for (UiAction action : UiAction.values()) {
            List<KeyBinding> all = map.all(action);
            assertThat(all).as("%s declarations", action).isNotEmpty();
            assertThat(all.get(0)).as("%s canonical is first", action).isEqualTo(map.canonical(action));
            assertThat(map.aliases(action)).as("%s aliases", action).isEqualTo(all.subList(1, all.size()));
            assertThat(map.aliases(action)).as("%s aliases exclude canonical", action).doesNotContain(all.get(0));
        }
    }

    /**
     * A character bound as KEY_TYPED in one context must not have its VK
     * pressed form (plain or shifted) bound elsewhere, or a KEY_PRESSED then
     * KEY_TYPED pair can double-fire across a context change.
     */
    @Test
    void typedLettersHaveNoPressedVkPairInTheMap() {
        for (UiAction action : UiAction.values()) {
            for (KeyBinding binding : map.all(action)) {
                if (binding.stroke().getKeyEventType() != KeyEvent.KEY_TYPED) {
                    continue;
                }
                char keyChar = binding.stroke().getKeyChar();
                if (keyChar == '\n' || keyChar == '\t' || keyChar == '\b' || keyChar == ' ') {
                    continue;
                }
                char upper = Character.toUpperCase(keyChar);
                if (!Character.isLetter(upper)) {
                    continue;
                }
                int vk = KeyEvent.VK_A + (upper - 'A');
                for (UiAction otherAction : UiAction.values()) {
                    for (KeyBinding other : map.all(otherAction)) {
                        if (other.stroke().getKeyEventType() == KeyEvent.KEY_TYPED) {
                            continue;
                        }
                        // Ctrl-letter presses produce no printable typed character, so they never pair with it.
                        if ((other.stroke().getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0) {
                            continue;
                        }
                        if (other.stroke().getKeyCode() == vk) {
                            throw new AssertionError(
                                    "Typed '" + upper + "' used by " + action + " but VK_" + upper + " is bound by "
                                            + otherAction + " as a pressed key");
                        }
                    }
                }
            }
        }
    }

    /**
     * No two actions may share a keystroke where both are live. {@link Context#ANY}
     * overlaps every context; {@link Context#PROMPT} overlaps the views (the prompt
     * is drawn over them). The only permitted overlap is the prompt answer set:
     * Y/Space/Enter for yes and N for no.
     */
    @Test
    void noTwoActionsShareAKeystrokeInAnOverlappingContext() {
        Map<KeyStroke, List<Bound>> byStroke = new LinkedHashMap<>();
        for (UiAction action : UiAction.values()) {
            for (KeyBinding binding : map.all(action)) {
                byStroke.computeIfAbsent(binding.stroke(), stroke -> new ArrayList<>())
                        .add(new Bound(action, binding));
            }
        }

        for (Map.Entry<KeyStroke, List<Bound>> entry : byStroke.entrySet()) {
            List<Bound> bounds = entry.getValue();
            for (int i = 0; i < bounds.size(); i++) {
                for (int j = i + 1; j < bounds.size(); j++) {
                    Bound left = bounds.get(i);
                    Bound right = bounds.get(j);
                    if (!overlaps(left.binding.context(), right.binding.context())) {
                        continue;
                    }
                    assertThat(isWhitelistedPromptOverlap(left.action(), right.action(), entry.getKey())
                            || isDetailsShadow(left, right))
                            .as("%s and %s both bind %s", left.action(), right.action(), entry.getKey())
                            .isTrue();
                }
            }
        }
    }

    @Test
    void promptAnswersAreTypedLettersWithNoPressedVk() {
        assertThat(KeyMap.defaults().label(UiAction.PROMPT_YES)).isEqualTo("y");
        assertThat(KeyMap.defaults().label(UiAction.PROMPT_NO)).isEqualTo("n");
        for (KeyBinding binding : KeyMap.defaults().all(UiAction.PROMPT_YES)) {
            if (binding.stroke().getKeyEventType() != java.awt.event.KeyEvent.KEY_TYPED) {
                continue;
            }
            assertThat(Character.toLowerCase(binding.stroke().getKeyChar())).isEqualTo('y');
        }
        for (KeyBinding binding : KeyMap.defaults().all(UiAction.PROMPT_NO)) {
            if (binding.stroke().getKeyEventType() != java.awt.event.KeyEvent.KEY_TYPED) {
                continue;
            }
            assertThat(Character.toLowerCase(binding.stroke().getKeyChar())).isEqualTo('n');
        }
    }

    @Test
    void bookmarksUseTheLessMarkKeys() {
        assertThat(map.label(UiAction.TOGGLE_BOOKMARK, KeyFlavour.PAGER)).isEqualTo("m");
        assertThat(map.label(UiAction.BOOKMARKS_VIEW, KeyFlavour.PAGER)).isEqualTo("M");
        assertThat(map.label(UiAction.BOOKMARKS_VIEW)).isEqualTo("F2");
        assertThat(contextsOf(UiAction.TOGGLE_BOOKMARK)).containsExactly(Context.LOG_VIEW, Context.MATCHES_VIEW,
                Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW, Context.BOOKMARKS_VIEW,
                Context.DETAILS_VIEW, Context.DETAILS_VIEW);
        assertThat(contextsOf(UiAction.BOOKMARKS_VIEW)).containsExactly(Context.LOG_VIEW, Context.MATCHES_VIEW,
                Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW, Context.LOG_VIEW, Context.MATCHES_VIEW);
    }

    @Test
    void f2TogglesTheBookmarksViewInEveryViewAndIsUnguarded() {
        KeyStroke f2 = KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0);

        for (Context context : List.of(Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW)) {
            assertThat(map.actionFor(new KeyBinding(f2, KeyGuard.UNGUARDED, context)))
                    .contains(UiAction.BOOKMARKS_VIEW);
        }
        assertThat(map.actionFor(new KeyBinding(f2, KeyGuard.UNGUARDED, Context.PROMPT))).isEmpty();
        assertThat(map.labels(UiAction.BOOKMARKS_VIEW, KeyFlavour.DESKTOP)).isEqualTo("F2 / Ctrl-Shift-B");
        assertThat(map.label(UiAction.BOOKMARKS_VIEW, KeyFlavour.PAGER)).isEqualTo("M");
    }

    @Test
    void ctrlShiftCIsTheGuardedDesktopKeyThatOpensCopyAsInEveryViewButNotThePrompt() {
        KeyStroke ctrlShiftC = KeyStroke.getKeyStroke(KeyEvent.VK_C,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);

        assertThat(map.label(UiAction.COPY_AS)).isEqualTo("Ctrl-Shift-C");
        assertThat(map.label(UiAction.COPY_AS, KeyFlavour.PAGER)).isEqualTo("Ctrl-Shift-C");
        assertThat(contextsOf(UiAction.COPY_AS)).containsExactly(Context.LOG_VIEW, Context.MATCHES_VIEW,
                Context.BOOKMARKS_VIEW);
        for (Context context : List.of(Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW)) {
            assertThat(map.actionFor(new KeyBinding(ctrlShiftC, KeyGuard.GUARDED, context)))
                    .contains(UiAction.COPY_AS);
        }
        assertThat(map.actionFor(new KeyBinding(ctrlShiftC, KeyGuard.GUARDED, Context.PROMPT))).isEmpty();
        assertThat(map.all(UiAction.COPY_AS)).allSatisfy(binding -> {
            assertThat(binding.guard()).isEqualTo(KeyGuard.GUARDED);
            assertThat(binding.flavour()).isEqualTo(KeyFlavour.DESKTOP);
        });
        assertThat(map.actionFor(new KeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                KeyGuard.GUARDED, Context.ANY))).contains(UiAction.COPY);
    }

    @Test
    void ctrlShiftBIsTheGuardedDesktopKeyThatOpensTheBookmarksViewFromLogAndMatches() {
        KeyStroke ctrlShiftB = KeyStroke.getKeyStroke(KeyEvent.VK_B,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);

        assertThat(map.labels(UiAction.BOOKMARKS_VIEW, KeyFlavour.DESKTOP)).isEqualTo("F2 / Ctrl-Shift-B");
        assertThat(map.labels(UiAction.BOOKMARKS_VIEW, KeyFlavour.PAGER)).isEqualTo("M");
        for (Context context : List.of(Context.LOG_VIEW, Context.MATCHES_VIEW)) {
            assertThat(map.actionFor(new KeyBinding(ctrlShiftB, KeyGuard.GUARDED, context)))
                    .contains(UiAction.BOOKMARKS_VIEW);
        }
        assertThat(map.actionFor(new KeyBinding(ctrlShiftB, KeyGuard.GUARDED, Context.BOOKMARKS_VIEW))).isEmpty();
        assertThat(map.all(UiAction.BOOKMARKS_VIEW)).allSatisfy(binding -> {
            if (binding.stroke().getKeyCode() == KeyEvent.VK_F2) {
                assertThat(binding.guard()).isEqualTo(KeyGuard.UNGUARDED);
                assertThat(binding.context()).isIn(Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW);
            } else {
                assertThat(binding.guard()).isEqualTo(KeyGuard.GUARDED);
                assertThat(binding.context()).isIn(Context.LOG_VIEW, Context.MATCHES_VIEW);
            }
        });
    }

    @Test
    void theBookmarksViewHasItsOwnKeysForRemoveShowInLogMatchesAndDetails() {
        KeyStroke ctrlB = KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK);
        KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        KeyStroke f4 = KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0);

        assertThat(map.actionFor(new KeyBinding(ctrlB, KeyGuard.GUARDED, Context.BOOKMARKS_VIEW)))
                .contains(UiAction.TOGGLE_BOOKMARK);
        assertThat(map.actionFor(new KeyBinding(enter, KeyGuard.GUARDED, Context.BOOKMARKS_VIEW)))
                .contains(UiAction.SHOW_IN_LOG);
        assertThat(map.actionFor(new KeyBinding(f4, KeyGuard.UNGUARDED, Context.BOOKMARKS_VIEW)))
                .contains(UiAction.TOGGLE_MATCHES);
        assertThat(map.actionFor(new KeyBinding(KeyStroke.getKeyStroke(Character.valueOf('&'), 0), KeyGuard.GUARDED,
                Context.BOOKMARKS_VIEW).tagged(KeyFlavour.PAGER))).contains(UiAction.TOGGLE_MATCHES);
        assertThat(map.actionFor(new KeyBinding(KeyStroke.getKeyStroke(Character.valueOf('m'), 0), KeyGuard.GUARDED,
                Context.BOOKMARKS_VIEW).tagged(KeyFlavour.PAGER))).contains(UiAction.TOGGLE_BOOKMARK);
        // The label of a key for one context: `&` shows a match in the log in the Matches view, but
        // opens the matches from the Bookmarks view, where Enter is the key that shows in the log.
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.SHOW_IN_LOG)).isEqualTo("&");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.SHOW_IN_LOG, Context.BOOKMARKS_VIEW))
                .isEqualTo("Enter");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.SHOW_IN_LOG, Context.MATCHES_VIEW))
                .isEqualTo("&");
        assertThat(map.label(UiAction.TOGGLE_BOOKMARK, Context.BOOKMARKS_VIEW)).isEqualTo("Ctrl-B");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.TOGGLE_BOOKMARK, Context.BOOKMARKS_VIEW))
                .isEqualTo("m");
        assertThat(contextsOf(UiAction.MATCH_DETAILS)).contains(Context.BOOKMARKS_VIEW);
        // Narrow and exclude work on a search, not on the bookmarks.
        assertThat(contextsOf(UiAction.NARROW)).doesNotContain(Context.BOOKMARKS_VIEW);
        assertThat(contextsOf(UiAction.EXCLUDE)).doesNotContain(Context.BOOKMARKS_VIEW);
        assertThat(map.bindingsFor(Context.BOOKMARKS_VIEW)).contains(map.canonical(UiAction.CANCEL))
                .doesNotContain(map.canonical(UiAction.PROMPT_YES));
    }

    @Test
    void ctrlBIsTheDesktopToggleInTheViewsOnly() {
        KeyStroke ctrlB = KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK);
        assertThat(KeyMap.defaults().actionFor(new KeyBinding(ctrlB, KeyGuard.GUARDED, Context.LOG_VIEW)))
                .contains(UiAction.TOGGLE_BOOKMARK);
        assertThat(KeyMap.defaults().actionFor(new KeyBinding(ctrlB, KeyGuard.GUARDED, Context.MATCHES_VIEW)))
                .contains(UiAction.TOGGLE_BOOKMARK);
        assertThat(map.labels(UiAction.TOGGLE_BOOKMARK, KeyFlavour.DESKTOP)).isEqualTo("Ctrl-B");
        assertThat(map.labels(UiAction.TOGGLE_BOOKMARK, KeyFlavour.PAGER)).isEqualTo("m");
        assertThat(map.label(UiAction.TOGGLE_BOOKMARK)).isEqualTo("Ctrl-B");
    }

    @Test
    void narrowAndExcludeAreGuardedTypedKeysInTheViews() {
        KeyMap keyMap = KeyMap.defaults();

        assertThat(keyMap.label(UiAction.NARROW)).isEqualTo("+");
        assertThat(keyMap.label(UiAction.EXCLUDE)).isEqualTo("-");
        for (UiAction action : List.of(UiAction.NARROW, UiAction.EXCLUDE)) {
            assertThat(keyMap.all(action)).allSatisfy(binding -> {
                assertThat(binding.stroke().getKeyEventType()).isEqualTo(KeyEvent.KEY_TYPED);
                assertThat(binding.guard()).isEqualTo(KeyGuard.GUARDED);
                assertThat(binding.context()).isIn(Context.LOG_VIEW, Context.MATCHES_VIEW);
            });
        }
    }

    @Test
    void theNarrowToActivityKeysAreGuardedTypedKeysInTheThreeViewsAndNotInPrompts() {
        Map<UiAction, Character> keys = Map.of(UiAction.NARROW_TO_ACTIVITY, 'a', UiAction.NARROW_TO_REQUEST, 'A');

        keys.forEach((action, keyChar) -> {
            assertThat(map.label(action)).isEqualTo(String.valueOf(keyChar));
            assertThat(map.all(action)).hasSize(3).allSatisfy(binding -> {
                assertThat(binding.stroke().getKeyEventType()).isEqualTo(KeyEvent.KEY_TYPED);
                assertThat(binding.stroke().getKeyChar()).isEqualTo(keyChar);
                assertThat(binding.guard()).isEqualTo(KeyGuard.GUARDED);
            });
            assertThat(map.all(action)).extracting(KeyBinding::context)
                    .containsExactly(Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW);
            assertThat(action.group()).isEqualTo(UiAction.Group.ACTIVITY);
        });
    }

    @Test
    void theRowMenuOpensWithShiftF10AndTheContextMenuKeyInTheThreeViewsAndTheFieldMenuInTheDetails() {
        assertThat(map.label(UiAction.CONTEXT_MENU)).isEqualTo("Shift-F10");
        assertThat(map.all(UiAction.CONTEXT_MENU)).hasSize(8).allSatisfy(binding -> {
            assertThat(binding.guard()).isEqualTo(KeyGuard.GUARDED);
            assertThat(binding.context()).isIn(Context.LOG_VIEW, Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW,
                    Context.DETAILS_VIEW);
        });
        assertThat(map.all(UiAction.CONTEXT_MENU)).extracting(b -> b.stroke().getKeyCode())
                .containsOnly(KeyEvent.VK_F10, KeyEvent.VK_CONTEXT_MENU);
        assertThat(map.all(UiAction.CONTEXT_MENU)).extracting(b -> b.stroke().getKeyCode() == KeyEvent.VK_F10)
                .containsOnly(true, false);
        assertThat(map.all(UiAction.CONTEXT_MENU)).filteredOn(b -> b.stroke().getKeyCode() == KeyEvent.VK_F10)
                .allSatisfy(b -> assertThat(b.stroke().getModifiers() & InputEvent.SHIFT_DOWN_MASK).isNotZero());
    }

    @Test
    void labelsUseHumanKeyNames() {
        assertThat(map.label(UiAction.NEXT_MATCH)).isEqualTo("F3");
        assertThat(map.label(UiAction.PREVIOUS_MATCH)).isEqualTo("Shift-F3");
        assertThat(map.label(UiAction.OPEN_SEARCH)).isEqualTo("Ctrl-F");
        assertThat(map.label(UiAction.OPEN_SEARCH_BACKWARD)).isEqualTo("?");
        assertThat(map.label(UiAction.CANCEL)).isEqualTo("Esc");
        assertThat(map.label(UiAction.PAGE_DOWN)).isEqualTo("PgDn");
        assertThat(map.label(UiAction.PAGE_UP)).isEqualTo("PgUp");
        assertThat(map.label(UiAction.TOGGLE_MATCHES)).isEqualTo("F4");
        assertThat(map.label(UiAction.HELP)).isEqualTo("h");
        assertThat(map.label(UiAction.SET_MARK)).isEqualTo("Ctrl-Space");
        assertThat(KeyMap.format(map.aliases(UiAction.OPEN_SEARCH).get(0).stroke())).isEqualTo("/");
        assertThat(KeyMap.format(map.aliases(UiAction.TOGGLE_MATCHES).get(0).stroke())).isEqualTo("&");
        assertThat(KeyMap.format(map.aliases(UiAction.PAGE_DOWN).get(0).stroke())).isEqualTo("Space");
        assertThat(KeyMap.format(
                KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)))
                .isEqualTo("Ctrl-Shift-F3");
    }

    @Test
    void labelWithAliasesListsCanonicalFirst() {
        assertThat(map.labelWithAliases(UiAction.NEXT_MATCH)).isEqualTo("F3 / n / Ctrl-S");
        assertThat(map.labelWithAliases(UiAction.PREVIOUS_MATCH)).isEqualTo("Shift-F3 / N / Ctrl-R / Ctrl-Shift-F3");
        assertThat(map.labelWithAliases(UiAction.OPEN_SEARCH)).isEqualTo("Ctrl-F / /");
        assertThat(map.labelWithAliases(UiAction.CANCEL)).isEqualTo("Esc / Ctrl-G");
        assertThat(map.labelWithAliases(UiAction.TOGGLE_MATCHES)).isEqualTo("F4 / &");
        assertThat(map.labelWithAliases(UiAction.PROMPT_YES)).isEqualTo("y / Y / Space / Enter");
        assertThat(map.labelWithAliases(UiAction.PROMPT_NO)).isEqualTo("n / N");
        assertThat(map.label(UiAction.PROMPT_YES)).isEqualTo("y");
        assertThat(map.label(UiAction.PROMPT_NO)).isEqualTo("n");
    }

    @Test
    void characterBindingsAreKeyTyped() {
        record TypedCase(UiAction action, char keyChar) {}

        List<TypedCase> cases = List.of(new TypedCase(UiAction.OPEN_SEARCH, '/'),
                new TypedCase(UiAction.OPEN_SEARCH_BACKWARD, '?'),
                new TypedCase(UiAction.SEARCH_SELECTION_FORWARD, '*'),
                new TypedCase(UiAction.SEARCH_SELECTION_BACKWARD, '#'),
                new TypedCase(UiAction.TOGGLE_MATCHES, '&'), new TypedCase(UiAction.NARROW, '+'),
                new TypedCase(UiAction.EXCLUDE, '-'), new TypedCase(UiAction.HELP, 'h'),
                new TypedCase(UiAction.PAGE_UP, 'b'), new TypedCase(UiAction.TOGGLE_BOOKMARK, 'm'),
                new TypedCase(UiAction.BOOKMARKS_VIEW, 'M'), new TypedCase(UiAction.MATCH_DETAILS, 'i'),
                new TypedCase(UiAction.TOGGLE_CURSOR, 'v'), new TypedCase(UiAction.NEXT_MATCH, 'n'),
                new TypedCase(UiAction.PREVIOUS_MATCH, 'N'),
                new TypedCase(UiAction.PROMPT_NO, 'n'), new TypedCase(UiAction.PROMPT_NO, 'N'),
                new TypedCase(UiAction.PROMPT_YES, 'y'), new TypedCase(UiAction.PROMPT_YES, 'Y'));

        for (TypedCase typed : cases) {
            boolean found = false;
            for (KeyBinding binding : map.all(typed.action())) {
                if (binding.stroke().getKeyEventType() == KeyEvent.KEY_TYPED
                        && binding.stroke().getKeyChar() == typed.keyChar()) {
                    found = true;
                }
            }
            assertThat(found).as("%s binds typed '%s'", typed.action(), typed.keyChar).isTrue();
        }
    }

    @Test
    void aliasesAreOnlyLiveWhereTheyBelong() {
        // The prompt's "no" does not shadow prev-match outside the prompt, and
        // `N` is not a prompt answer in the views.
        assertThat(map.bindingsFor(Context.PROMPT)).contains(map.canonical(UiAction.PROMPT_NO))
                .contains(map.canonical(UiAction.PROMPT_YES));
        assertThat(contextsOf(UiAction.PROMPT_NO)).containsExactly(Context.PROMPT, Context.PROMPT);
        assertThat(contextsOf(UiAction.PREVIOUS_MATCH)).containsExactly(Context.ANY, Context.LOG_VIEW,
                Context.MATCHES_VIEW, Context.ANY, Context.ANY);
        assertThat(contextsOf(UiAction.TOGGLE_MATCHES)).containsExactly(Context.LOG_VIEW, Context.LOG_VIEW,
                Context.BOOKMARKS_VIEW, Context.BOOKMARKS_VIEW);
        assertThat(contextsOf(UiAction.SHOW_IN_LOG)).containsExactly(Context.MATCHES_VIEW, Context.MATCHES_VIEW,
                Context.MATCHES_VIEW, Context.BOOKMARKS_VIEW);

        // Esc and the cursor keys are live in every view, prompt answers are not.
        assertThat(map.bindingsFor(Context.LOG_VIEW)).contains(map.canonical(UiAction.CANCEL))
                .contains(map.canonical(UiAction.CURSOR_UP))
                .doesNotContain(map.canonical(UiAction.PROMPT_YES));
    }

    @Test
    void everyBindingHasAFlavour() {
        for (UiAction action : UiAction.values()) {
            for (KeyBinding binding : map.all(action)) {
                assertThat(binding.flavour()).as("%s %s", action, binding).isNotNull();
            }
        }
    }

    @Test
    void bindingsAreTaggedWithTheirTradition() {
        assertThat(flavours(UiAction.OPEN_SEARCH)).containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER);
        assertThat(flavours(UiAction.NEXT_MATCH)).containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER,
                KeyFlavour.PAGER, KeyFlavour.EMACS);
        assertThat(flavours(UiAction.PREVIOUS_MATCH)).containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER,
                KeyFlavour.PAGER, KeyFlavour.EMACS, KeyFlavour.DESKTOP);
        assertThat(flavours(UiAction.CANCEL)).containsExactly(KeyFlavour.DESKTOP, KeyFlavour.EMACS);
        assertThat(flavours(UiAction.PAGE_UP)).containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER);
        assertThat(flavours(UiAction.PAGE_DOWN)).containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER);
        assertThat(flavours(UiAction.SET_MARK)).containsExactly(KeyFlavour.EMACS);
        assertThat(flavours(UiAction.CURSOR_UP)).containsExactly(KeyFlavour.DESKTOP);
        assertThat(flavours(UiAction.COPY)).containsExactly(KeyFlavour.DESKTOP);
        assertThat(flavours(UiAction.TOGGLE_MATCHES)).containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER,
                KeyFlavour.DESKTOP, KeyFlavour.PAGER);
        assertThat(flavours(UiAction.PROMPT_YES)).containsOnly(KeyFlavour.DESKTOP);
    }

    @Test
    void labelShowsThePreferredFlavourWithADesktopFallback() {
        assertThat(map.label(UiAction.NEXT_MATCH)).isEqualTo("F3");
        assertThat(map.label(UiAction.NEXT_MATCH, KeyFlavour.PAGER)).isEqualTo("n");
        assertThat(map.label(UiAction.NEXT_MATCH, KeyFlavour.EMACS)).isEqualTo("Ctrl-S");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.NEXT_MATCH)).isEqualTo("n");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.PREVIOUS_MATCH)).isEqualTo("N");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.OPEN_SEARCH)).isEqualTo("/");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.TOGGLE_MATCHES)).isEqualTo("&");
        assertThat(map.withFlavour(KeyFlavour.EMACS).label(UiAction.CANCEL)).isEqualTo("Ctrl-G");

        // No key of its own in that flavour: the desktop key, else the canonical one.
        assertThat(map.withFlavour(KeyFlavour.EMACS).label(UiAction.OPEN_SEARCH)).isEqualTo("Ctrl-F");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.COPY)).isEqualTo("Ctrl-C");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.TOGGLE_CURSOR)).isEqualTo("v");
        assertThat(map.withFlavour(KeyFlavour.EMACS).label(UiAction.TOGGLE_CURSOR)).isEqualTo("F7");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.SET_MARK)).isEqualTo("Ctrl-Space");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.HELP)).isEqualTo("h");
        assertThat(map.withFlavour(KeyFlavour.EMACS).label(UiAction.HELP)).isEqualTo("h");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.OPEN_SEARCH_BACKWARD)).isEqualTo("?");
        assertThat(map.label(UiAction.OPEN_SEARCH_BACKWARD)).isEqualTo("?");
    }

    @Test
    void labelsAreExactPerFlavourAndEmptyWhenAFlavourHasNoKey() {
        assertThat(map.labels(UiAction.NEXT_MATCH, KeyFlavour.PAGER)).isEqualTo("n");
        assertThat(map.labels(UiAction.PREVIOUS_MATCH, KeyFlavour.DESKTOP)).isEqualTo("Shift-F3 / Ctrl-Shift-F3");
        assertThat(map.labels(UiAction.COPY, KeyFlavour.EMACS)).isEmpty();
        assertThat(map.hasFlavour(UiAction.COPY, KeyFlavour.EMACS)).isFalse();
        assertThat(map.hasFlavour(UiAction.CANCEL, KeyFlavour.EMACS)).isTrue();
    }

    @Test
    void thePreferredMapFollowsThePreference() {
        assertThat(KeyMap.preferred().label(UiAction.NEXT_MATCH)).isEqualTo("F3");

        KeyMap.setPreferredFlavour(KeyFlavour.PAGER);
        assertThat(KeyMap.preferred().label(UiAction.NEXT_MATCH)).isEqualTo("n");
        assertThat(KeyMap.preferred().flavour()).isEqualTo(KeyFlavour.PAGER);
        assertThat(KeyMap.defaults().label(UiAction.NEXT_MATCH)).as("the defaults stay desktop").isEqualTo("F3");

        KeyMap.setPreferredFlavour(null);
        assertThat(KeyMap.preferredFlavour()).isEqualTo(KeyFlavour.DESKTOP);
    }

    @Test
    void flavoursParseLeniently() {
        assertThat(KeyFlavour.fromName("PAGER")).isEqualTo(KeyFlavour.PAGER);
        assertThat(KeyFlavour.fromName(" emacs ")).isEqualTo(KeyFlavour.EMACS);
        assertThat(KeyFlavour.fromName("nonsense")).isEqualTo(KeyFlavour.DESKTOP);
        assertThat(KeyFlavour.fromName(null)).isEqualTo(KeyFlavour.DESKTOP);
    }

    @Test
    void noKeyIsBoundTwiceInTheSameContext() {
        Map<String, UiAction> seen = new LinkedHashMap<>();
        for (UiAction action : UiAction.values()) {
            for (KeyBinding binding : map.all(action)) {
                String key = binding.context() + " " + binding.stroke();
                UiAction previous = seen.put(key, action);
                assertThat(previous).as("%s is bound by both %s and %s", key, previous, action).isNull();
            }
        }
    }

    @Test
    void theCursorToggleIsF7AndVInBothViewsAndScrollLockInBothToo() {
        assertThat(map.label(UiAction.TOGGLE_CURSOR)).isEqualTo("F7");
        List<KeyBinding> all = map.all(UiAction.TOGGLE_CURSOR);
        assertThat(all).hasSize(3);

        KeyBinding f7 = all.get(0);
        assertThat(f7.stroke()).isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        assertThat(f7.guard()).isEqualTo(KeyGuard.UNGUARDED);
        assertThat(f7.flavour()).isEqualTo(KeyFlavour.DESKTOP);

        KeyBinding v = all.get(1);
        assertThat(v.stroke().getKeyEventType()).isEqualTo(KeyEvent.KEY_TYPED);
        assertThat(v.stroke().getKeyChar()).isEqualTo('v');
        assertThat(v.guard()).isEqualTo(KeyGuard.GUARDED);
        assertThat(v.flavour()).isEqualTo(KeyFlavour.PAGER);

        KeyBinding scrollLock = all.get(2);
        assertThat(scrollLock.stroke()).isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_SCROLL_LOCK, 0));
        assertThat(scrollLock.guard()).isEqualTo(KeyGuard.GUARDED);
        assertThat(scrollLock.flavour()).isEqualTo(KeyFlavour.DESKTOP);

        assertThat(contextsOf(UiAction.TOGGLE_CURSOR)).containsOnly(Context.ANY);
        assertThat(map.actionFor(f7)).contains(UiAction.TOGGLE_CURSOR);
        assertThat(map.actionFor(scrollLock)).contains(UiAction.TOGGLE_CURSOR);
        assertThat(map.labelWithAliases(UiAction.TOGGLE_CURSOR)).isEqualTo("F7 / v / ScrLk");
    }

    @Test
    void scrollLockIsADesktopAliasOfTheCursorToggleOnly() {
        KeyStroke scrollLock = KeyStroke.getKeyStroke(KeyEvent.VK_SCROLL_LOCK, 0);
        List<UiAction> bound = map.bindingsFor(Context.LOG_VIEW).stream()
                .filter(binding -> binding.stroke().equals(scrollLock))
                .map(binding -> map.actionFor(binding).orElseThrow())
                .toList();
        assertThat(bound).containsExactly(UiAction.TOGGLE_CURSOR);
        assertThat(map.label(UiAction.TOGGLE_CURSOR, KeyFlavour.PAGER)).isEqualTo("v");
    }

    @Test
    void escapeStaysTheCancelKeyAndNoSearchKeyIsBoundTwice() {
        assertThat(map.canonical(UiAction.CANCEL).stroke()).isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        // Open-search keys never double as next/previous.
        for (KeyBinding open : map.all(UiAction.OPEN_SEARCH)) {
            assertThat(map.all(UiAction.NEXT_MATCH)).extracting(KeyBinding::stroke).doesNotContain(open.stroke());
            assertThat(map.all(UiAction.PREVIOUS_MATCH)).extracting(KeyBinding::stroke)
                    .doesNotContain(open.stroke());
        }
        assertThat(contextsOf(UiAction.NEXT_MATCH)).contains(Context.MATCHES_VIEW).contains(Context.LOG_VIEW);
        assertThat(contextsOf(UiAction.PREVIOUS_MATCH)).contains(Context.MATCHES_VIEW).contains(Context.LOG_VIEW);
    }

    @Test
    void theDetailsKeysResolveInTheDetailsContextOnly() {
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.DETAILS_PREVIOUS)).containsExactlyInAnyOrder("Alt-Up",
                "Shift-F3", "N");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.DETAILS_NEXT)).containsExactlyInAnyOrder("Alt-Down", "F3",
                "n");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.DETAILS_NARROW_FIELD)).containsExactly("+");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.DETAILS_EXCLUDE_FIELD)).containsExactly("-");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.DETAILS_MESSAGE_FIELD)).containsExactly("f");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.DETAILS_FIRST)).containsExactly("Home");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.DETAILS_LAST)).containsExactly("End");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.OPEN_DETAILS)).containsExactly("Enter");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.TOGGLE_BOOKMARK)).containsExactlyInAnyOrder("m", "Ctrl-B");
        assertThat(keysIn(Context.DETAILS_VIEW, UiAction.CONTEXT_MENU)).containsExactlyInAnyOrder("Shift-F10",
                "Menu");

        for (UiAction action : new UiAction[]{UiAction.DETAILS_PREVIOUS, UiAction.DETAILS_NEXT,
                UiAction.DETAILS_FIRST, UiAction.DETAILS_LAST, UiAction.DETAILS_NARROW_FIELD,
                UiAction.DETAILS_EXCLUDE_FIELD, UiAction.DETAILS_MESSAGE_FIELD}) {
            assertThat(map.all(action)).as("%s", action).allSatisfy(binding -> assertThat(binding.context())
                    .isEqualTo(Context.DETAILS_VIEW));
            assertThat(action.group()).isEqualTo(UiAction.Group.DETAILS);
            assertThat(action.description()).startsWith("Details:");
        }
        assertThat(map.all(UiAction.DETAILS_NEXT)).filteredOn(b -> b.flavour() == KeyFlavour.PAGER)
                .extracting(b -> KeyMap.format(b.stroke())).containsExactly("n");
        assertThat(map.withFlavour(KeyFlavour.PAGER).label(UiAction.DETAILS_NEXT)).isEqualTo("n");
        assertThat(map.label(UiAction.DETAILS_NEXT)).isEqualTo("Alt-Down");
    }

    @Test
    void theViewKeysAreNotLiveInTheDetails() {
        List<KeyBinding> live = map.bindingsFor(Context.DETAILS_VIEW);

        assertThat(live).doesNotContain(map.all(UiAction.NARROW_TO_ACTIVITY).getFirst(),
                map.all(UiAction.NARROW).getFirst(), map.all(UiAction.TOGGLE_MATCHES).getFirst(),
                map.all(UiAction.BOOKMARKS_VIEW).getFirst(), map.all(UiAction.COPY_AS).getFirst());
        assertThat(live).contains(map.canonical(UiAction.CANCEL), map.canonical(UiAction.CURSOR_UP),
                map.canonical(UiAction.COPY), map.canonical(UiAction.HELP));
        assertThat(map.bindingsFor(Context.LOG_VIEW)).noneMatch(binding -> binding.context() == Context.DETAILS_VIEW);
    }

    private List<String> keysIn(Context context, UiAction action) {
        return map.all(action).stream().filter(binding -> binding.context() == context)
                .map(binding -> KeyMap.format(binding.stroke())).toList();
    }

    private List<KeyFlavour> flavours(UiAction action) {
        return map.all(action).stream().map(KeyBinding::flavour).toList();
    }

    private List<Context> contextsOf(UiAction action) {
        return map.all(action).stream().map(KeyBinding::context).toList();
    }

    private static List<String> tokens(UiAction action) {
        return KeyMap.defaults().all(action).stream().map(binding -> token(binding.stroke())).toList();
    }

    /** Normalises a stroke to the token used by the prompt-overlap whitelist. */
    private static String token(KeyStroke stroke) {
        if (stroke.getKeyEventType() == KeyEvent.KEY_TYPED) {
            char keyChar = Character.toUpperCase(stroke.getKeyChar());
            if (keyChar == ' ') {
                return "SPACE";
            }
            if (keyChar == '\n') {
                return "ENTER";
            }
            if (keyChar == 'Y') {
                return "Y";
            }
            if (keyChar == 'N') {
                return "N";
            }
            return String.valueOf(keyChar);
        }
        return switch (stroke.getKeyCode()) {
            case KeyEvent.VK_ENTER -> "ENTER";
            case KeyEvent.VK_SPACE -> "SPACE";
            default -> "KEY" + stroke.getKeyCode();
        };
    }

    private static boolean isWhitelistedPromptOverlap(UiAction left, UiAction right, KeyStroke stroke) {
        if (!involvesPromptAnswer(left) && !involvesPromptAnswer(right)) {
            return false;
        }
        String token = token(stroke);
        return (anyIs(left, right, UiAction.PROMPT_YES) && Set.of("Y", "SPACE", "ENTER").contains(token))
                || (anyIs(left, right, UiAction.PROMPT_NO) && "N".equals(token));
    }

    /**
     * While the details are open their keys win over the ANY ones they rebind: F3 and Shift-F3 step to the next
     * and previous event instead of the next and previous match.
     */
    private static boolean isDetailsShadow(Bound left, Bound right) {
        return shadows(left, right, UiAction.DETAILS_NEXT, UiAction.NEXT_MATCH)
                || shadows(left, right, UiAction.DETAILS_PREVIOUS, UiAction.PREVIOUS_MATCH);
    }

    private static boolean shadows(Bound left, Bound right, UiAction details, UiAction shadowed) {
        for (Bound[] pair : new Bound[][]{{left, right}, {right, left}}) {
            if (pair[0].action() == details && pair[0].binding().context() == Context.DETAILS_VIEW
                    && pair[1].action() == shadowed && pair[1].binding().context() == Context.ANY) {
                return true;
            }
        }
        return false;
    }

    private static boolean involvesPromptAnswer(UiAction action) {
        return action == UiAction.PROMPT_YES || action == UiAction.PROMPT_NO;
    }

    private static boolean anyIs(UiAction left, UiAction right, UiAction action) {
        return left == action || right == action;
    }

    /** Two contexts overlap when either is ANY or when they are the same bar a prompt/other-view pair. */
    private static boolean overlaps(Context left, Context right) {
        if (left == right) {
            return true;
        }
        if (left == Context.ANY || right == Context.ANY) {
            return true;
        }
        return left == Context.PROMPT || right == Context.PROMPT;
    }

    private record Bound(UiAction action, KeyBinding binding) {}
}
