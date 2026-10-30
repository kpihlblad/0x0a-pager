package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.clip.ClipboardAccess;
import se.grynna.pager.ui.clip.InMemoryClipboard;

import java.awt.datatransfer.Transferable;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPrefillTest {

    private final InMemoryClipboard clipboard = new InMemoryClipboard();
    private final SearchPrefill prefill = new SearchPrefill(clipboard);

    private static final Optional<String> NONE = Optional.empty();

    @Test
    void aOneLineClipboardTextPrefillsTheNewPrompt() {
        clipboard.setText("  timeout  ");

        assertThat(prefill.open(NONE, NONE, NONE)).contains("timeout");
    }

    @Test
    void aLogSelectionBeatsANewClipboardText() {
        clipboard.setText("timeout");

        assertThat(prefill.open(Optional.of("alpha"), Optional.of("error"), NONE)).contains("alpha");
    }

    @Test
    void aMultiLineClipboardTextIsIgnored() {
        clipboard.setText("first line\nsecond line");

        assertThat(prefill.open(NONE, NONE, NONE)).isEmpty();
        assertThat(prefill.open(NONE, Optional.of("error"), NONE)).contains("error");
    }

    @Test
    void aClipboardTextLongerThanTheLimitIsIgnored() {
        clipboard.setText("x".repeat(PromptText.MAX_SELECTION_LENGTH + 1));

        assertThat(prefill.open(NONE, NONE, NONE)).isEmpty();
    }

    @Test
    void aBlankClipboardIsIgnored() {
        clipboard.setText("   ");

        assertThat(prefill.open(NONE, NONE, NONE)).isEmpty();
    }

    @Test
    void theSameClipboardTextOnTheSecondOpenFallsBackToTheActiveTerm() {
        clipboard.setText("timeout");
        assertThat(prefill.open(NONE, NONE, NONE)).contains("timeout");

        assertThat(prefill.open(NONE, Optional.of("error"), NONE)).contains("error");
        assertThat(prefill.open(NONE, NONE, NONE)).isEmpty();
    }

    @Test
    void aClipboardTextEqualToTheLastSubmittedTermFallsBackToTheActiveTerm() {
        clipboard.setText("timeout");

        assertThat(prefill.open(NONE, Optional.of("error"), Optional.of("timeout"))).contains("error");
    }

    @Test
    void aClipboardTextEqualToTheLastSubmittedTermIsNotOfferedEvenWithoutAnActiveTerm() {
        clipboard.setText("timeout");

        assertThat(prefill.open(NONE, NONE, Optional.of("timeout"))).isEmpty();
    }

    @Test
    void aDifferentClipboardTextIsOfferedAgainAfterAnEarlierOne() {
        clipboard.setText("timeout");
        prefill.open(NONE, NONE, NONE);

        clipboard.setText("refused");

        assertThat(prefill.open(NONE, Optional.of("error"), NONE)).contains("refused");
    }

    @Test
    void aSelectionStillRemembersTheClipboardItSaw() {
        clipboard.setText("timeout");
        assertThat(prefill.open(Optional.of("alpha"), NONE, NONE)).contains("alpha");

        assertThat(prefill.open(NONE, Optional.of("error"), NONE)).contains("error");
    }

    @Test
    void theActiveTermPrefillsWhenTheClipboardIsEmpty() {
        assertThat(prefill.open(NONE, Optional.of("error"), NONE)).contains("error");
        assertThat(prefill.open(NONE, Optional.of("  "), NONE)).isEmpty();
    }

    @Test
    void anUnreadableClipboardCountsAsEmpty() {
        ClipboardAccess broken = new ClipboardAccess() {
            @Override
            public void setContents(Transferable contents) {}

            @Override
            public Transferable contents() {
                throw new IllegalStateException("clipboard busy");
            }
        };
        SearchPrefill unreadable = new SearchPrefill(broken);

        assertThat(unreadable.open(NONE, Optional.of("error"), NONE)).contains("error");
        assertThat(unreadable.open(NONE, NONE, NONE)).isEmpty();
    }

    @Test
    void aTextCopiedBeforeTheSeedIsNotOfferedAsNew() {
        clipboard.setText("copied before 0x0A Pager started");
        prefill.seed();

        assertThat(prefill.open(NONE, NONE, NONE)).isEmpty();
        assertThat(prefill.open(NONE, Optional.of("error"), NONE)).contains("error");
    }

    @Test
    void aTextCopiedAfterTheSeedIsOffered() {
        clipboard.setText("copied before 0x0A Pager started");
        prefill.seed();

        clipboard.setText("copied afterwards");

        assertThat(prefill.open(NONE, NONE, NONE)).contains("copied afterwards");
    }

    @Test
    void onlyTheFirstSeedCounts() {
        clipboard.setText("first");
        prefill.seed();
        clipboard.setText("second");
        prefill.seed();

        assertThat(prefill.open(NONE, NONE, NONE)).contains("second");
    }

    @Test
    void seedingAnUnreadableClipboardIsHarmless() {
        ClipboardAccess broken = new ClipboardAccess() {
            @Override
            public void setContents(Transferable contents) {}

            @Override
            public Transferable contents() {
                throw new IllegalStateException("clipboard busy");
            }
        };
        SearchPrefill unreadable = new SearchPrefill(broken);

        unreadable.seed();

        assertThat(unreadable.open(NONE, Optional.of("error"), NONE)).contains("error");
    }
}
