package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.FieldTerm;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeChipsTest {

    private static final Font FONT = new Font(Font.DIALOG, Font.PLAIN, 12);

    @Test
    void rendersOneChipPerTermWithItsSign() throws Exception {
        ScopeChips chips = create(term -> {});
        onEdt(() -> chips.setTerms(List.of("timeout"), List.of("healthcheck")));

        assertThat(texts(chips)).containsExactly("+\"timeout\"", "\u00d7", "\u2212\"healthcheck\"", "\u00d7");
        assertThat(onEdt(() -> chips.isVisible())).isTrue();
    }

    @Test
    void hiddenWithoutTextTerms() throws Exception {
        ScopeChips chips = create(term -> {});

        onEdt(() -> chips.setTerms(List.of("timeout"), List.of()));
        assertThat(onEdt(() -> chips.isVisible())).isTrue();

        onEdt(() -> chips.setTerms(List.of(), List.of()));
        assertThat(onEdt(() -> chips.isVisible())).isFalse();
        assertThat(texts(chips)).isEmpty();
    }

    @Test
    void theCrossCallsTheRemovalCallbackWithItsTerm() throws Exception {
        List<String> removed = new CopyOnWriteArrayList<>();
        ScopeChips chips = create(removed::add);
        onEdt(() -> chips.setTerms(List.of("timeout"), List.of("healthcheck")));

        onEdt(() -> buttons(chips).get(1).doClick());

        assertThat(removed).containsExactly("healthcheck");
    }

    @Test
    void longTermsAreElidedWithAnEllipsis() throws Exception {
        String longTerm = "a".repeat(40);
        ScopeChips chips = create(term -> {});
        onEdt(() -> chips.setTerms(List.of(longTerm), List.of()));

        String expected = "+\"" + "a".repeat(ScopeChips.ELIDE_AT) + "\u2026\"";
        assertThat(texts(chips)).contains(expected);
        assertThat(ScopeChips.chipText("+", "short")).isEqualTo("+\"short\"");
    }

    @Test
    void moreThanThreeTermsCollapseIntoACount() throws Exception {
        ScopeChips chips = create(term -> {});
        onEdt(() -> chips.setTerms(List.of("one", "two"), List.of("three", "four", "five")));

        assertThat(buttons(chips)).hasSize(ScopeChips.MAX_CHIPS);
        assertThat(texts(chips)).contains("+2 more");
    }

    @Test
    void fieldTermsShowTheirFieldAndValueWithoutTheMarker() throws Exception {
        String debug = FieldTerm.of("level", "DEBUG").orElseThrow().encode();
        String host = FieldTerm.of("host", "a*").orElseThrow().encode();
        ScopeChips chips = create(term -> {});
        onEdt(() -> chips.setTerms(List.of(debug), List.of(host, "healthcheck")));

        assertThat(texts(chips)).contains("+level=\"DEBUG\"", "\u2212host=\"a*\"", "\u2212\"healthcheck\"");
        assertThat(texts(chips)).noneMatch(text -> text.indexOf('\uE000') >= 0);
        assertThat(ScopeChips.chipText("+", debug)).isEqualTo("+level=\"DEBUG\"");
        assertThat(ScopeChips.chipText("+", "a=\"b\"")).isEqualTo("+\"a=\"b\"\"");
    }

    private static ScopeChips create(Consumer<String> onRemove) throws Exception {
        return onEdt(() -> new ScopeChips(FONT, onRemove));
    }

    private static List<String> texts(Container root) {
        List<String> texts = new ArrayList<>();
        for (Component c : all(root)) {
            if (c instanceof JLabel label) {
                texts.add(label.getText());
            } else if (c instanceof JButton button) {
                texts.add(button.getText());
            }
        }
        return texts;
    }

    private static List<JButton> buttons(Container root) {
        return all(root).stream().filter(JButton.class::isInstance).map(JButton.class::cast).toList();
    }

    private static List<Component> all(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            found.add(c);
            if (c instanceof Container container) {
                found.addAll(all(container));
            }
        }
        return found;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }
}
