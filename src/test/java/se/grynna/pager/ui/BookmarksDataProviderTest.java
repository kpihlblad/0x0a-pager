package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.AfterBoundaryLine;
import se.grynna.pager.splunk.BeforeBoundaryLine;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.SearchHit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarksDataProviderTest {

    private static final Instant T0 = Instant.parse("2023-01-01T12:00:00Z");
    private static final String EMPTY = "No bookmarks yet. Press m on a line to bookmark it.";

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    private static LoadedLogLine event(long seconds, String message) {
        Instant time = T0.plusSeconds(seconds);
        LoadedLogLine line = new LoadedLogLine(MsgType.JSON, null, 0, "cd" + seconds, "bkt", time, "raw", time, time,
                message, false, Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cd" + seconds, "_bkt", "bkt")));
        return line;
    }

    private static BookmarksDataProvider provider(boolean firstLineOnly, LoadedLogLine... events) {
        return new BookmarksDataProvider(List.of(events), firstLineOnly, EMPTY);
    }

    @Test
    void rowsAreTheWholeBookmarkedEventsInOrder() {
        BookmarksDataProvider provider = provider(false, event(10, "single"), event(20, "first\nsecond\nthird"),
                event(30, "last"));

        assertThat(provider.rows().eventCount()).isEqualTo(3);
        assertThat(provider.rows().rowCount()).isEqualTo(5);
        assertThat(((LoadedLogLine) provider.getLine(0, false)).getMessage()).isEqualTo("single");
        LoadedLogLine continuation = (LoadedLogLine) provider.getLine(2, false);
        assertThat(continuation.getMessage()).isEqualTo("second");
        assertThat(continuation.isContinuation()).isTrue();
        assertThat(provider.hitAt(3).splunkTime()).isEqualTo(T0.plusSeconds(20));
        assertThat(provider.offsetWithin(3)).isEqualTo(2);
        assertThat(provider.rowOfHit(SearchHit.from(event(30, "last")))).isEqualTo(4);
        assertThat(provider.rowOfHit(SearchHit.from(event(25, "absent")))).isEqualTo(-1);
        assertThat(provider.jump(T0.plusSeconds(21))).isEqualTo(1);
        assertThat(provider.jump(SearchHit.from(event(10, "single")))).isZero();
        assertThat(provider.jumpToLatest()).isEqualTo(4);
    }

    @Test
    void emptyStateIsOneDimRowWithTheHint() {
        BookmarksDataProvider provider = provider(false);

        assertThat(provider.rows().rowCount()).isZero();
        assertThat(provider.getLine(0, false)).isInstanceOfSatisfying(AfterBoundaryLine.class,
                after -> assertThat(after.message()).isEqualTo(EMPTY));
        assertThat(provider.getLine(1, false)).isInstanceOfSatisfying(AfterBoundaryLine.class,
                after -> assertThat(after.message()).isEmpty());
        assertThat(provider.getLine(-1, false)).isInstanceOf(BeforeBoundaryLine.class);
        assertThat(provider.getStatistics().loadedLines()).isZero();
        assertThat(provider.getSegmentInfo().isInitialized()).isFalse();
    }

    @Test
    void theHintNamesTheToggleKeyOfTheKeyMap() {
        assertThat(BookmarksDataProvider.emptyHint(KeyMap.defaults().withFlavour(KeyFlavour.PAGER)))
                .isEqualTo("No bookmarks yet. Press m on a line to bookmark it.");
        assertThat(BookmarksDataProvider.emptyHint(KeyMap.defaults()))
                .isEqualTo("No bookmarks yet. Press Ctrl-B on a line to bookmark it.");
    }

    @Test
    void plainStartAndEndRowsSurroundTheEvents() {
        BookmarksDataProvider provider = provider(false, event(10, "a"), event(20, "b"));

        assertThat(provider.getLine(-1, false)).isInstanceOfSatisfying(BeforeBoundaryLine.class,
                before -> assertThat(before.message()).isEqualTo(BookmarksDataProvider.FIRST_BOOKMARK));
        assertThat(provider.getLine(-2, false)).isInstanceOfSatisfying(BeforeBoundaryLine.class,
                before -> assertThat(before.message()).isEmpty());
        assertThat(provider.getLine(2, false)).isInstanceOfSatisfying(AfterBoundaryLine.class,
                after -> assertThat(after.message()).isEqualTo(BookmarksDataProvider.LAST_BOOKMARK));
        assertThat(provider.getLine(3, false)).isInstanceOfSatisfying(AfterBoundaryLine.class,
                after -> assertThat(after.message()).isEmpty());
        assertThat(provider.getBeforeBoundary().line()).hasValue(0);
        assertThat(provider.getAfterBoundary().line()).hasValue(2);
        assertThat(provider.getNowBoundary().line()).isEmpty();
        assertThat(provider.peekLine(0)).isPresent();
        assertThat(provider.peekLine(-1)).isPresent();
    }

    @Test
    void suffixIsOnlyTheHiddenLineCountAndNeverMatchInFields() {
        BookmarksDataProvider whole = provider(false, event(10, "a\nb"), event(20, "c"));
        assertThat(whole.suffixAt(0)).isNull();
        assertThat(whole.suffixAt(2)).isNull();

        BookmarksDataProvider firstLine = provider(true, event(10, "a\nb\nc"), event(20, "d"), event(30, "e\nf"));
        assertThat(firstLine.firstLineOnly()).isTrue();
        assertThat(firstLine.rows().rowCount()).isEqualTo(3);
        assertThat(firstLine.suffixAt(0)).isEqualTo("⏎+2");
        assertThat(firstLine.suffixAt(1)).isNull();
        assertThat(firstLine.suffixAt(2)).isEqualTo("⏎+1");
        assertThat(((LoadedLogLine) firstLine.getLine(0, false)).getMessage()).isEqualTo("a");
    }

    @Test
    void refreshSwapsTheEventsAndKeepsTheMode() {
        BookmarksDataProvider provider = provider(true, event(10, "a\nb"));
        assertThat(provider.rows().rowCount()).isEqualTo(1);

        provider.refresh(List.of(event(10, "a\nb"), event(20, "c\nd")));

        assertThat(provider.rows().eventCount()).isEqualTo(2);
        assertThat(provider.rows().rowCount()).isEqualTo(2);
        assertThat(provider.firstLineOnly()).isTrue();

        provider.refresh(List.of());
        assertThat(provider.rows().rowCount()).isZero();
        assertThat(provider.getLine(0, false)).isInstanceOf(AfterBoundaryLine.class);
    }

    @Test
    void gapAndDateRulesComeFromTheSharedBase() {
        BookmarksDataProvider provider = provider(false, event(0, "a"), event(1, "b"), event(2, "c"), event(3, "d"),
                event(4, "e"), event(10_000, "f"));

        assertThat(provider.ruleBefore(0)).isInstanceOf(GapRule.Date.class);
        assertThat(provider.ruleBefore(1)).isInstanceOf(GapRule.None.class);
        assertThat(provider.ruleBefore(5)).isInstanceOf(GapRule.Gap.class);
        assertThat(provider.ruleBefore(99)).isInstanceOf(GapRule.None.class);
    }

    @Test
    void wholeEventTextIsEveryRowAsTheLogShowsItRegardlessOfFirstLineMode() {
        List<String> text = BookmarksDataProvider.wholeEventText(List.of(event(10, "a\nb"), event(20, "c")));

        assertThat(text).hasSize(3);
        assertThat(text.get(0)).endsWith("a").isNotEqualTo("a");
        assertThat(text.get(1).strip()).isEqualTo("b");
        assertThat(text.get(1)).startsWith(" ");
        assertThat(text.get(2)).endsWith("c");
        assertThat(text.get(0)).hasSameSizeAs(text.get(2));
    }
}
