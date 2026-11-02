package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SegmentInfo;
import se.grynna.pager.ui.sprattel.LoadingAnimationTimer;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.Theme;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HelpOverlayTest {

    @BeforeAll
    static void initFormatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @Test
    void listsExactlyTheBoundActions() {
        KeyMap keyMap = KeyMap.defaults();
        InputHandler handler = new InputHandler(
                new CursorManager(mock(LogDataProvider.class), mock(LogViewPort.class)),
                mock(LogViewPort.class));
        Set<UiAction> bound = handler.boundActions();

        Mood mood = new Mood(keyMap, bound);

        assertThat(mood.actions()).containsExactlyInAnyOrderElementsOf(bound);
        assertThat(mood.actions()).hasSize(bound.size());
    }

    @Test
    void showsOneColumnPerFlavourWithTheKeysOfThatFlavour() {
        Mood mood = new Mood(KeyMap.defaults(), Set.of(UiAction.NEXT_MATCH));

        assertThat(mood.cell(UiAction.NEXT_MATCH, KeyFlavour.DESKTOP)).isEqualTo("F3");
        assertThat(mood.cell(UiAction.NEXT_MATCH, KeyFlavour.PAGER)).isEqualTo("n");
        assertThat(mood.cell(UiAction.NEXT_MATCH, KeyFlavour.EMACS)).isEqualTo("Ctrl-S");

        String text = mood.getHelpText();
        assertThat(text).contains("Action", "Desktop", "Pager (less)", "Emacs", "Next match", "F3", "n", "Ctrl-S");
    }

    @Test
    void aFlavourWithoutAKeyShowsTheFallbackKeyDimmed() {
        Mood mood = new Mood(KeyMap.defaults(), Set.of(UiAction.COPY, UiAction.PAGE_DOWN));

        assertThat(mood.hasKey(UiAction.COPY, KeyFlavour.EMACS)).isFalse();
        assertThat(mood.cell(UiAction.COPY, KeyFlavour.EMACS)).isEqualTo("Ctrl-C");
        assertThat(mood.cell(UiAction.COPY, KeyFlavour.DESKTOP)).isEqualTo("Ctrl-C");
        assertThat(mood.cell(UiAction.PAGE_DOWN, KeyFlavour.PAGER)).isEqualTo("Space");
        assertThat(mood.hasKey(UiAction.PAGE_DOWN, KeyFlavour.PAGER)).isTrue();
    }

    @Test
    void thePreferredFlavourComesFirst() {
        assertThat(new Mood(KeyMap.defaults(), Set.of()).columns())
                .containsExactly(KeyFlavour.DESKTOP, KeyFlavour.PAGER, KeyFlavour.EMACS);
        assertThat(new Mood(KeyMap.defaults().withFlavour(KeyFlavour.PAGER), Set.of()).columns())
                .containsExactly(KeyFlavour.PAGER, KeyFlavour.DESKTOP, KeyFlavour.EMACS);
        assertThat(new Mood(KeyMap.defaults().withFlavour(KeyFlavour.EMACS), Set.of()).columns())
                .containsExactly(KeyFlavour.EMACS, KeyFlavour.DESKTOP, KeyFlavour.PAGER);
    }

    @Test
    void listsTheBookmarkToggleWithCtrlBAndM() {
        Mood mood = new Mood(KeyMap.defaults(), Set.of(UiAction.TOGGLE_BOOKMARK));

        assertThat(mood.cell(UiAction.TOGGLE_BOOKMARK, KeyFlavour.DESKTOP)).isEqualTo("Ctrl-B");
        assertThat(mood.cell(UiAction.TOGGLE_BOOKMARK, KeyFlavour.PAGER)).isEqualTo("m");
    }

    @Test
    void listsTheBookmarksViewWithF2AndCtrlShiftBAndCapitalM() {
        Mood mood = new Mood(KeyMap.defaults(), Set.of(UiAction.BOOKMARKS_VIEW));

        assertThat(mood.cell(UiAction.BOOKMARKS_VIEW, KeyFlavour.DESKTOP)).isEqualTo("F2 / Ctrl-Shift-B");
        assertThat(mood.cell(UiAction.BOOKMARKS_VIEW, KeyFlavour.PAGER)).isEqualTo("M");
    }

    @Test
    void listsTheCursorToggleWithF7AndVAndScrollLock() {
        Mood mood = new Mood(KeyMap.defaults(), Set.of(UiAction.TOGGLE_CURSOR));

        assertThat(mood.cell(UiAction.TOGGLE_CURSOR, KeyFlavour.DESKTOP)).isEqualTo("F7 / ScrLk");
        assertThat(mood.cell(UiAction.TOGGLE_CURSOR, KeyFlavour.PAGER)).isEqualTo("v");
        assertThat(mood.getHelpText()).contains("Toggle cursor mode");
    }

    @Test
    void thePagerAmpersandIsListedForTheBookmarksViewToo() {
        Mood mood = new Mood(KeyMap.defaults().withFlavour(KeyFlavour.PAGER),
                Set.of(UiAction.SHOW_IN_LOG, UiAction.TOGGLE_MATCHES));

        assertThat(mood.cell(UiAction.SHOW_IN_LOG, KeyFlavour.PAGER)).isEqualTo("&");
        assertThat(mood.cell(UiAction.TOGGLE_MATCHES, KeyFlavour.PAGER)).isEqualTo("&");
        assertThat(mood.getHelpText()).contains("Show this match in the log",
                "Show or hide the matches (in the Bookmarks view: go to the matches)");
    }

    @Test
    void listsTheNarrowToActivityKeysUnderTheActivitySectionAndTheRowMenu() {
        Set<UiAction> actions = Set.of(UiAction.NARROW_TO_ACTIVITY, UiAction.NARROW_TO_REQUEST, UiAction.CONTEXT_MENU);
        Mood mood = new Mood(KeyMap.defaults(), actions);

        assertThat(mood.actions()).containsExactlyInAnyOrderElementsOf(actions);
        assertThat(mood.cell(UiAction.NARROW_TO_ACTIVITY, KeyFlavour.DESKTOP)).isEqualTo("a");
        assertThat(mood.cell(UiAction.NARROW_TO_REQUEST, KeyFlavour.DESKTOP)).isEqualTo("A");
        assertThat(mood.cell(UiAction.CONTEXT_MENU, KeyFlavour.DESKTOP)).isEqualTo("Shift-F10 / Menu");
        assertThat(mood.getHelpText()).contains("ACTIVITY", UiAction.NARROW_TO_ACTIVITY.description());
    }

    @Test
    void injectedLoginInstructionRowReflectsStateAndProvidesStyleSpans() throws Exception {
        var row = new AtomicReference<>(Mood.LOGIN_INSTRUCTION);
        Mood mood = new Mood(Clock.systemUTC(), KeyMap.defaults(), Set.of(UiAction.LOGIN), row::get);

        int lineNo = mood.getLoginInstructionLine();
        LoadedLogLine initial = (LoadedLogLine) mood.getLine(lineNo, false);
        assertThat(initial.getMessage()).isEqualTo(Mood.LOGIN_INSTRUCTION);
        assertThat(mood.styleSpans(lineNo)).isNotEmpty();

        String loggedIn = Mood.loggedInInstruction(KeyMap.defaults());
        row.set(loggedIn);
        LoadedLogLine updated = (LoadedLogLine) mood.getLine(lineNo, false);
        assertThat(updated.getMessage()).isEqualTo(loggedIn);
        assertThat(mood.getHelpText()).contains(loggedIn).doesNotContain(Mood.LOGIN_INSTRUCTION);
    }

    @Test
    void nightSkyRendersInfinitelyOnDemandAndFillsConfiguredHorizontalWidth() {
        Mood mood = new Mood();

        assertThat(mood.getBeforeBoundary().line()).isEmpty();
        for (int lineNo : new int[]{-1, -45, -80, -81, -500, -10_000, -1_000_000}) {
            LogLine line = mood.getLine(lineNo, false);
            assertThat(line).as("line %d", lineNo).isInstanceOf(LoadedLogLine.class);
            LoadedLogLine loaded = (LoadedLogLine) line;
            assertThat(loaded.getMessage()).hasSize(mood.getSkyWidth()).doesNotContain("♦");
        }

        assertThat(mood.setSkyWidth(140)).isTrue();
        assertThat(mood.getSkyWidth()).isEqualTo(140);
        assertThat(((LoadedLogLine) mood.getLine(-1, false)).getMessage()).hasSize(140);

        assertThat(mood.setSkyWidth(260)).isTrue();
        assertThat(mood.getSkyWidth()).isEqualTo(260);
        assertThat(((LoadedLogLine) mood.getLine(-1, false)).getMessage()).hasSize(260);
    }

    @Test
    void nightSkyEnsuresStarsHaveRoomToShineAndStylesMoonAndStarContours() {
        var generator = new se.grynna.pager.ui.stars.ASCIINightSkyGenerator(160, 50, new java.util.Random(42L))
                .setMoonCount(2)
                .setUseBigMoons(true)
                .setAlienCount(1)
                .setSurpriseCount(1)
                .setAsteroidCount(4)
                .setBigStarCount(10)
                .setSmallStarCount(150)
                .setRequireNullSurrounding(true);

        String sky = generator.generateSkyChunkAsString();
        assertThat(sky).doesNotContain("♦").contains("(◕ ‿ ◕ ✿)");
        for (char ch : "ʔʕ◕‿✿☜☞♡◡☾✦★".toCharArray()) {
            assertThat(Fontalizer.getFont().canDisplay(ch)).as("canDisplay(%s)", ch).isTrue();
        }

        Mood.StyledLine moonRow = Mood.styleSkyLine("   |  |   --o--   (◕ ‿ ◕ ✿)");
        assertThat(moonRow.spans()).anySatisfy(span -> {
            assertThat(moonRow.text().substring(span.start(), span.end())).isEqualTo("|");
            assertThat(span.foreground().get()).isEqualTo(DarculaColors.COMMENT);
        });
        assertThat(moonRow.spans()).anySatisfy(span -> {
            assertThat(moonRow.text().substring(span.start(), span.end())).contains("o");
            assertThat(span.foreground().get()).isEqualTo(DarculaColors.CYAN);
        });
        assertThat(moonRow.spans()).anySatisfy(span -> {
            assertThat(moonRow.text().substring(span.start(), span.end())).contains("◕");
            assertThat(span.foreground().get()).isEqualTo(DarculaColors.PINK);
        });
    }

    @Test
    void styleSpansResolveThemeColoursDynamicallyAndRenderCleanly() {
        DarculaColors.use(Theme.DARK);
        Mood mood = new Mood(KeyMap.defaults(), Set.of(UiAction.NEXT_MATCH));
        int lineNo = mood.getLoginInstructionLine();
        List<StyleSpan> spans = mood.styleSpans(lineNo);
        assertThat(spans).isNotEmpty();

        var darkColor = spans.getFirst().foreground().get();
        DarculaColors.use(Theme.LIGHT);
        try {
            var lightColor = spans.getFirst().foreground().get();
            assertThat(lightColor).isNotEqualTo(darkColor).isEqualTo(DarculaColors.FOREGROUND);

            BufferedImage img = new BufferedImage(800, 40, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = img.createGraphics();
            try {
                var metrics = Fontalizer.getFontMetrics(g2d);
                LineRenderer renderer = new LineRenderer(new LoadingAnimationTimer(new JPanel(), ""), metrics,
                        new Rectangle(0, 0, 800, 40));
                renderer.setCharSize(new LogViewer.CharacterSize(metrics.charWidth('W'),
                        metrics.getAscent() + metrics.getDescent(), metrics.getAscent(), metrics.getDescent()));
                DecoratedLogLine dl = new DecoratedLogLine(mood.getLine(lineNo, false));
                dl.setStyleSpans(spans);
                renderer.render(g2d, dl, null, null, 0, 0);
                renderer.render(g2d, dl, null, null, 20, 0);
            } finally {
                g2d.dispose();
            }
        } finally {
            DarculaColors.use(Theme.DARK);
        }
    }

    @Test
    void showKeyboardHelpSwapsToMoodInMainViewerAndRestoresPreviousViewOnClose() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SwingUtilities.invokeAndWait(() -> {
            LogViewer viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(800, 600);
            viewer.cursorManager().updateSize(30, 100);

            LogDataProvider logProvider = stubLogProvider();
            setLogProvider(viewer, logProvider);
            viewer.cursorManager().setLogProvider(logProvider);
            viewer.cursorManager().gotoLine(15);
            int savedPageStart = viewer.cursorManager().getPageStart();

            viewer.showKeyboardHelp();

            assertThat(viewer.cursorManager().captureViewState().provider()).isInstanceOf(Mood.class);
            assertThat(viewer.visibleLines()).extracting(dl -> dl.getLogLine())
                    .allSatisfy(line -> assertThat(((LoadedLogLine) line).getSegmentId()).isEqualTo("mood"));
            assertThat(viewer.visibleLines())
                    .anySatisfy(dl -> assertThat(((LoadedLogLine) dl.getLogLine()).getMessage())
                            .contains("Press L to change scope"));

            assertThat(viewer.closeKeyboardHelp()).isTrue();
            assertThat(viewer.cursorManager().captureViewState().provider()).isSameAs(logProvider);
            assertThat(viewer.cursorManager().getPageStart()).isEqualTo(savedPageStart);
            assertThat(viewer.closeKeyboardHelp()).isFalse();
        });
    }

    private static LogDataProvider stubLogProvider() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Boundary before = new Boundary(BoundaryType.BEFORE, now.minusSeconds(60), 0);
        Boundary after = new Boundary(BoundaryType.AFTER, now, 100);
        Boundary nowBoundary = new Boundary(BoundaryType.NOW, now, 100);
        return new LogDataProvider() {
            @Override
            public int getMaxOobPerSide() {
                return 3;
            }

            @Override
            public LogLine getLine(int line, boolean edge) {
                return new LoadedLogLine(LoadedLogLine.MsgType.RAW, "seg", line, "cb-" + line, "bkt",
                        now.plusSeconds(line), "line " + line, now, now.plusSeconds(line), "line " + line, false,
                        Level.INFO);
            }

            @Override
            public int jumpToLatest() {
                return 0;
            }

            @Override
            public int jump(Instant timestamp) {
                return 0;
            }

            @Override
            public void unfreezeBeforeBoundary() {}

            @Override
            public void unfreezeAfterBoundary() {}

            @Override
            public boolean cancelSearch() {
                return false;
            }

            @Override
            public LogStatistics getStatistics() {
                return new LogStatistics(LogStatistics.Status.LOADED, 100, now.minusSeconds(60), now);
            }

            @Override
            public Boundary getBeforeBoundary() {
                return before;
            }

            @Override
            public void setBeforeBoundary(Instant t) {}

            @Override
            public Boundary getAfterBoundary() {
                return after;
            }

            @Override
            public void setAfterBoundary(Instant t) {}

            @Override
            public Boundary getNowBoundary() {
                return nowBoundary;
            }

            @Override
            public void setBoundaryExpansionListener(BoundaryExpansionListener listener) {}

            @Override
            public SegmentInfo getSegmentInfo() {
                return SegmentInfo.uninitialized();
            }
        };
    }

    private static void setLogProvider(LogViewer viewer, LogDataProvider provider) {
        try {
            Field f = LogViewer.class.getDeclaredField("logProvider");
            f.setAccessible(true);
            f.set(viewer, provider);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
