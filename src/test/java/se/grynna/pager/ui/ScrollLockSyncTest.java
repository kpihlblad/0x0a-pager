package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ScrollLockSyncTest {

    /** A cursor mode the sync can read and switch, recording every switch. */
    private static final class Fake {
        final AtomicBoolean cursorMode = new AtomicBoolean(false);
        final List<Boolean> switches = new ArrayList<>();

        ScrollLockSync sync(Optional<Boolean> lockState) {
            return new ScrollLockSync(() -> lockState, cursorMode::get, on -> {
                switches.add(on);
                cursorMode.set(on);
            });
        }
    }

    @Test
    void aKeyPressWithAReadableLockStateFollowsThatStateInsteadOfToggling() {
        Fake fake = new Fake();
        fake.cursorMode.set(true);
        ScrollLockSync sync = fake.sync(Optional.of(true));

        sync.keyPressed();

        assertThat(fake.switches).containsExactly(false);
        assertThat(fake.cursorMode).isFalse();
    }

    @Test
    void aLockStateOffMeansCursorModeOn() {
        Fake fake = new Fake();
        ScrollLockSync sync = fake.sync(Optional.of(false));

        sync.keyPressed();

        assertThat(fake.switches).containsExactly(true);
    }

    @Test
    void anUnreadableLockStateMakesTheKeyToggleCursorMode() {
        Fake fake = new Fake();
        ScrollLockSync sync = fake.sync(Optional.empty());

        sync.keyPressed();
        sync.keyPressed();

        assertThat(fake.switches).containsExactly(true, false);
    }

    @Test
    void windowActivationSetsCursorModeFromAReadableLockState() {
        Fake fake = new Fake();
        fake.cursorMode.set(true);
        ScrollLockSync sync = fake.sync(Optional.of(true));

        sync.windowActivated();

        assertThat(fake.switches).containsExactly(false);
    }

    @Test
    void windowActivationLeavesCursorModeAloneWhenItAlreadyMatches() {
        Fake fake = new Fake();
        ScrollLockSync sync = fake.sync(Optional.of(true));

        sync.windowActivated();

        assertThat(fake.switches).isEmpty();
    }

    @Test
    void windowActivationDoesNothingWhenTheLockStateIsUnreadable() {
        Fake fake = new Fake();
        fake.cursorMode.set(true);
        ScrollLockSync sync = fake.sync(Optional.empty());

        sync.windowActivated();

        assertThat(fake.switches).isEmpty();
        assertThat(fake.cursorMode).isTrue();
    }
}
