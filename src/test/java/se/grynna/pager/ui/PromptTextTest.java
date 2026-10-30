package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTextTest {

    @Test
    void pasteBecomesOneTrimmedLine() {
        assertThat(PromptText.singleLine("first\nsecond\n")).isEqualTo("first second");
        assertThat(PromptText.singleLine("a\r\nb\rc")).isEqualTo("a b c");
        assertThat(PromptText.singleLine("  padded  ")).isEqualTo("padded");
        assertThat(PromptText.singleLine("\n\n")).isEmpty();
    }

    @Test
    void oneLineSelectionIsOfferedTrimmed() {
        assertThat(PromptText.fromSelection("  timeout  ")).isEqualTo(Optional.of("timeout"));
    }

    @Test
    void blankOrMultiLineSelectionsAreNotOffered() {
        assertThat(PromptText.fromSelection(null)).isEmpty();
        assertThat(PromptText.fromSelection("   ")).isEmpty();
        assertThat(PromptText.fromSelection("one\ntwo")).isEmpty();
        assertThat(PromptText.fromSelection("one\rtwo")).isEmpty();
    }

    @Test
    void selectionLongerThanTheLimitIsNotOffered() {
        String atLimit = "x".repeat(PromptText.MAX_SELECTION_LENGTH);

        assertThat(PromptText.fromSelection(atLimit)).isEqualTo(Optional.of(atLimit));
        assertThat(PromptText.fromSelection(atLimit + "x")).isEmpty();
    }
}
