package se.grynna.pager.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.ui.Bookmarks.Action;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookmarksTest {

    private static final Instant T = Instant.parse("2023-01-01T12:00:00Z");

    @Test
    @DisplayName("toggling unbookmarked events adds them all")
    void addAll() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        var b = event(1, "cb2");

        var result = bookmarks.toggle(List.of(a, b));

        assertThat(result).isEqualTo(new Bookmarks.ToggleResult(Action.ADDED, 2, 2));
        assertThat(bookmarks.contains(SearchHit.from(a))).isTrue();
        assertThat(bookmarks.contains(SearchHit.from(b))).isTrue();
    }

    @Test
    @DisplayName("toggling bookmarked events removes them all")
    void removeAll() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        var b = event(1, "cb2");
        bookmarks.toggle(List.of(a, b));

        var result = bookmarks.toggle(List.of(a, b));

        assertThat(result).isEqualTo(new Bookmarks.ToggleResult(Action.REMOVED, 2, 0));
        assertThat(bookmarks.size()).isZero();
        assertThat(bookmarks.contains(SearchHit.from(a))).isFalse();
    }

    @Test
    @DisplayName("mixed selection adds the missing events and keeps the bookmarked ones")
    void mixed() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        var b = event(1, "cb2");
        var c = event(2, "cb3");
        bookmarks.toggle(List.of(a));

        var result = bookmarks.toggle(List.of(a, b, c));

        assertThat(result).isEqualTo(new Bookmarks.ToggleResult(Action.ADDED, 2, 3));
        assertThat(hits(bookmarks)).containsExactly(SearchHit.from(a), SearchHit.from(b), SearchHit.from(c));
    }

    @Test
    @DisplayName("events are kept in EventOrder, regardless of toggle order")
    void ordering() {
        Bookmarks bookmarks = new Bookmarks();
        var late = event(5, "cb9");
        var early = event(1, "cb2");
        var middle = event(3, "cb5");

        bookmarks.toggle(List.of(late));
        bookmarks.toggle(List.of(early, middle));

        assertThat(hits(bookmarks)).containsExactly(SearchHit.from(early), SearchHit.from(middle),
                SearchHit.from(late));
    }

    @Test
    @DisplayName("events with equal splunk time are ordered by cd")
    void orderingWithEqualTimes() {
        Bookmarks bookmarks = new Bookmarks();
        var cd10 = event(0, "cb10");
        var cd9 = event(0, "cb9");

        bookmarks.toggle(List.of(cd10, cd9));

        assertThat(hits(bookmarks)).containsExactly(SearchHit.from(cd9), SearchHit.from(cd10));
    }

    @Test
    @DisplayName("duplicate identities in one call count once")
    void duplicates() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        var aAgain = event(0, "cb1");

        var added = bookmarks.toggle(List.of(a, aAgain, a));
        assertThat(added).isEqualTo(new Bookmarks.ToggleResult(Action.ADDED, 1, 1));

        var removed = bookmarks.toggle(List.of(aAgain, a));
        assertThat(removed).isEqualTo(new Bookmarks.ToggleResult(Action.REMOVED, 1, 0));
    }

    @Test
    @DisplayName("empty toggle changes nothing")
    void emptyToggle() {
        Bookmarks bookmarks = new Bookmarks();
        AtomicInteger notified = new AtomicInteger();
        bookmarks.addListener(notified::incrementAndGet);

        assertThat(bookmarks.toggle(List.of())).isEqualTo(new Bookmarks.ToggleResult(Action.UNCHANGED, 0, 0));
        assertThat(bookmarks.version()).isZero();
        assertThat(notified).hasValue(0);
    }

    @Test
    @DisplayName("version and listeners fire on real changes only")
    void versionAndListeners() {
        Bookmarks bookmarks = new Bookmarks();
        AtomicInteger notified = new AtomicInteger();
        bookmarks.addListener(notified::incrementAndGet);
        var a = event(0, "cb1");

        bookmarks.toggle(List.of(a));
        assertThat(bookmarks.version()).isEqualTo(1);
        assertThat(notified).hasValue(1);

        bookmarks.toggle(List.of(a));
        assertThat(bookmarks.version()).isEqualTo(2);
        assertThat(notified).hasValue(2);

        bookmarks.clear();
        assertThat(bookmarks.version()).isEqualTo(2);
        assertThat(notified).hasValue(2);
    }

    @Test
    @DisplayName("removed listeners are no longer called")
    void removeListener() {
        Bookmarks bookmarks = new Bookmarks();
        AtomicInteger notified = new AtomicInteger();
        Runnable listener = notified::incrementAndGet;
        bookmarks.addListener(listener);
        bookmarks.removeListener(listener);

        bookmarks.toggle(List.of(event(0, "cb1")));

        assertThat(notified).hasValue(0);
    }

    @Test
    @DisplayName("clear removes everything and notifies once; clearing an empty model is a no-op")
    void clear() {
        Bookmarks bookmarks = new Bookmarks();
        AtomicInteger notified = new AtomicInteger();
        bookmarks.addListener(notified::incrementAndGet);
        bookmarks.toggle(List.of(event(0, "cb1"), event(1, "cb2")));

        bookmarks.clear();
        assertThat(bookmarks.size()).isZero();
        assertThat(bookmarks.events()).isEmpty();
        assertThat(notified).hasValue(2);

        bookmarks.clear();
        assertThat(notified).hasValue(2);
    }

    @Test
    @DisplayName("events() is an immutable snapshot")
    void snapshot() {
        Bookmarks bookmarks = new Bookmarks();
        bookmarks.toggle(List.of(event(0, "cb1")));

        List<LoadedLogLine> snapshot = bookmarks.events();
        bookmarks.toggle(List.of(event(1, "cb2")));

        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(event(2, "cb3"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the same event from split rows counts as one bookmark")
    void identityAcrossRows() {
        Bookmarks bookmarks = new Bookmarks();
        var first = event(0, "cb1");
        var continuation = new LoadedLogLine(MsgType.RAW, null, 1, "cb1", "bkt", T, "raw", T, T, "more", true,
                Level.INFO);

        bookmarks.toggle(List.of(first));

        assertThat(bookmarks.contains(SearchHit.from(continuation))).isTrue();
        assertThat(bookmarks.toggle(List.of(continuation)).action()).isEqualTo(Action.REMOVED);
    }

    @Test
    @DisplayName("null arguments are rejected")
    void nulls() {
        Bookmarks bookmarks = new Bookmarks();
        assertThatThrownBy(() -> bookmarks.toggle(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bookmarks.contains(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("added bookmarks are unsaved until copied or exported")
    void unsavedTracksAdds() {
        Bookmarks bookmarks = new Bookmarks();
        assertThat(bookmarks.unsavedCount()).isZero();

        bookmarks.toggle(List.of(event(0, "cb1")));
        bookmarks.toggle(List.of(event(1, "cb2"), event(2, "cb3")));
        assertThat(bookmarks.unsavedCount()).isEqualTo(3);

        bookmarks.markAllSaved();
        assertThat(bookmarks.unsavedCount()).isZero();
        assertThat(bookmarks.size()).isEqualTo(3);

        bookmarks.toggle(List.of(event(3, "cb4")));
        assertThat(bookmarks.unsavedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a mixed toggle counts only the newly added bookmarks as unsaved")
    void unsavedOnlyCountsNewAdds() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        bookmarks.toggle(List.of(a));
        bookmarks.markAllSaved();

        bookmarks.toggle(List.of(a, event(1, "cb2")));

        assertThat(bookmarks.unsavedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("markSaved clears just those events and ignores events that are not bookmarks")
    void markSavedIsPerEvent() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        var b = event(1, "cb2");
        bookmarks.toggle(List.of(a, b));

        bookmarks.markSaved(List.of(a, event(9, "other")));

        assertThat(bookmarks.unsavedCount()).isEqualTo(1);
        bookmarks.markSaved(List.of(b));
        assertThat(bookmarks.unsavedCount()).isZero();
    }

    @Test
    @DisplayName("removing a bookmark removes it from the unsaved ones; clear resets")
    void removeAdjustsUnsaved() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        var b = event(1, "cb2");
        bookmarks.toggle(List.of(a, b));

        bookmarks.toggle(List.of(a));
        assertThat(bookmarks.unsavedCount()).isEqualTo(1);

        bookmarks.clear();
        assertThat(bookmarks.unsavedCount()).isZero();
    }

    @Test
    @DisplayName("removing a saved bookmark leaves the unsaved count alone")
    void removingSavedLeavesUnsaved() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        bookmarks.toggle(List.of(a));
        bookmarks.markAllSaved();
        bookmarks.toggle(List.of(event(1, "cb2")));

        bookmarks.toggle(List.of(a));

        assertThat(bookmarks.unsavedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("removeAll drops the given identities, notifies once, and adjusts unsaved")
    void removeAllByIdentity() {
        Bookmarks bookmarks = new Bookmarks();
        var a = event(0, "cb1");
        var b = event(1, "cb2");
        var c = event(2, "cb3");
        bookmarks.toggle(List.of(a, b, c));
        bookmarks.markSaved(List.of(c));
        AtomicInteger notified = new AtomicInteger();
        bookmarks.addListener(notified::incrementAndGet);

        int removed = bookmarks.removeAll(List.of(SearchHit.from(a), SearchHit.from(c), SearchHit.from(event(9, "x"))));

        assertThat(removed).isEqualTo(2);
        assertThat(hits(bookmarks)).containsExactly(SearchHit.from(b));
        assertThat(notified).hasValue(1);
        assertThat(bookmarks.unsavedCount()).isEqualTo(1);

        assertThat(bookmarks.removeAll(List.of(SearchHit.from(a)))).isZero();
        assertThat(notified).hasValue(1);
    }

    @Test
    @DisplayName("unsaved changes do not bump the version or notify")
    void savingIsNotAChange() {
        Bookmarks bookmarks = new Bookmarks();
        bookmarks.toggle(List.of(event(0, "cb1")));
        long version = bookmarks.version();
        AtomicInteger notified = new AtomicInteger();
        bookmarks.addListener(notified::incrementAndGet);

        bookmarks.markAllSaved();

        assertThat(bookmarks.version()).isEqualTo(version);
        assertThat(notified).hasValue(0);
    }

    private static List<SearchHit> hits(Bookmarks bookmarks) {
        return bookmarks.events().stream().map(SearchHit::from).toList();
    }

    private static LoadedLogLine event(int seconds, String cd) {
        Instant time = T.plusSeconds(seconds);
        return new LoadedLogLine(MsgType.RAW, null, 0, cd, "bkt", time, "raw-" + cd, time, time, "msg-" + cd, false,
                Level.INFO);
    }
}
