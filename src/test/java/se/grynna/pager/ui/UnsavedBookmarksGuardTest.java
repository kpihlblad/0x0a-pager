package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.UnsavedBookmarksGuard.Choice;
import se.grynna.pager.ui.UnsavedBookmarksGuard.Purpose;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UnsavedBookmarksGuardTest {

    private final AtomicInteger unsaved = new AtomicInteger(3);
    private final List<String> events = new ArrayList<>();
    private final AtomicReference<Runnable> exportContinuation = new AtomicReference<>();
    private Choice answer = Choice.CANCEL;
    private int asked;

    private final UnsavedBookmarksGuard guard = new UnsavedBookmarksGuard(unsaved::get, (purpose, count) -> {
        asked++;
        events.add("asked " + purpose + " " + count);
        return answer;
    }, new UnsavedBookmarksGuard.Actions() {
        @Override
        public void copyBookmarks() {
            events.add("copy");
        }

        @Override
        public void exportBookmarks(Runnable afterSuccessfulWrite) {
            events.add("export");
            exportContinuation.set(afterSuccessfulWrite);
        }
    });

    private void run(Purpose purpose) {
        guard.guard(purpose, () -> events.add("proceed"));
    }

    @Test
    void nothingUnsavedProceedsWithoutAsking() {
        unsaved.set(0);
        run(Purpose.QUIT);
        assertThat(events).containsExactly("proceed");
        assertThat(asked).isZero();
    }

    @Test
    void copyCopiesThenProceeds() {
        answer = Choice.COPY;
        run(Purpose.QUIT);
        assertThat(events).containsExactly("asked QUIT 3", "copy", "proceed");
    }

    @Test
    void proceedAnywayDoesNotSave() {
        answer = Choice.PROCEED;
        run(Purpose.LOG_OUT);
        assertThat(events).containsExactly("asked LOG_OUT 3", "proceed");
    }

    @Test
    void cancelStays() {
        answer = Choice.CANCEL;
        run(Purpose.QUIT);
        assertThat(events).containsExactly("asked QUIT 3");
    }

    @Test
    void aClosedDialogCountsAsCancel() {
        answer = null;
        run(Purpose.QUIT);
        assertThat(events).containsExactly("asked QUIT 3");
    }

    @Test
    void exportProceedsOnlyAfterASuccessfulWrite() {
        answer = Choice.EXPORT;
        run(Purpose.QUIT);

        assertThat(events).containsExactly("asked QUIT 3", "export");
        assertThat(exportContinuation.get()).isNotNull();

        exportContinuation.get().run();
        assertThat(events).containsExactly("asked QUIT 3", "export", "proceed");
    }

    @Test
    void aFailedOrCancelledExportNeverRunsTheContinuation() {
        answer = Choice.EXPORT;
        run(Purpose.LOG_OUT);

        // The Export dialog was cancelled or the write failed: nobody calls the continuation.
        assertThat(events).doesNotContain("proceed");
    }

    @Test
    void buttonLabelsFollowThePurpose() {
        assertThat(UnsavedBookmarksGuard.labels(Purpose.QUIT))
                .containsExactly("Copy and quit", "Export…", "Quit anyway", "Cancel");
        assertThat(UnsavedBookmarksGuard.labels(Purpose.LOG_OUT))
                .containsExactly("Copy and log out", "Export…", "Log out anyway", "Cancel");
    }

    @Test
    void theDialogTextCountsTheBookmarks() {
        assertThat(BookmarkNotices.unsaved(3)).isEqualTo("You have 3 bookmarks that are not exported or copied.");
        assertThat(BookmarkNotices.unsaved(1)).isEqualTo("You have 1 bookmark that is not exported or copied.");
    }
}
