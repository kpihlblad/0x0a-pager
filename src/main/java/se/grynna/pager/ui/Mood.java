package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.*;
import se.grynna.pager.utils.SbomTransformer;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static se.grynna.pager.splunk.BoundaryType.*;
import static se.grynna.pager.splunk.Level.INFO;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.RAW;

@Slf4j
public class Mood implements LogDataProvider {

    volatile int i = (int) (Math.random() * moods.length);
    private final String sign = calculateSign();
    private final String licence = SbomTransformer.transformSbom();

    public Mood() {
        this(Clock.systemUTC());
    }

    public Mood(Clock clock) {
        // Each Mood (demo session) takes its start time from the clock passed in here.
        this.now = clock.instant();
        this.afterBoundary = new Boundary(AFTER, now, signLines.length + licenceLines.length);
        this.nowBoundary = new Boundary(NOW, now.plusSeconds(3600), 1000 + signLines.length + licenceLines.length);
    }

    private String calculateSign() {
        return moods[i % moods.length] + banner;
    }

    private final String[] signLines = sign.lines().toArray(String[]::new);
    private final String[] licenceLines = licence.lines().toArray(String[]::new);
    private final String[] skyLines = Ascii.sky.lines().toArray(String[]::new);
    private final Instant now;

    private Boundary beforeBoundary = new Boundary(BEFORE, randomInstant(), -1000);
    private final Boundary afterBoundary;
    private final Boundary nowBoundary;

    @Override
    public int jumpToLatest() {
        return 0;
    }

    @Override
    public int getMaxOobPerSide() {
        return 3;
    }

    @Override
    public LogLine getLine(int lineNo, boolean ignore) {

        if (lineNo >= nowBoundary.line().get()) {
            return new NowBoundaryLine(lineNo, nowBoundary);
        }

        if (lineNo >= afterBoundary.line().get()) {
            return new AfterBoundaryLine(lineNo, afterBoundary);
        }
        if (lineNo < beforeBoundary.line().get()) {
            return new BeforeBoundaryLine(lineNo, beforeBoundary);
        }

        var ll = new LoadedLogLine(RAW, "mood", lineNo, "cb", "bkt", now, null, now, now, signLine(lineNo), true, INFO);
        return ll;
    }

    private String signLine(int line) {

        // if less than 0 use Ascii.sky instead, and if it's less than that just the line number
        if (line < 0) {
            // return the line from the top of Ascii.sky
            var skyLine = skyLines.length + line;
            if (skyLine < 0) {
                return "Line " + line + " is outside the skyLines. ";
            } else {
                return skyLines[skyLine];
            }
        }
        if (line >= signLines.length) {
            int licLine = line - signLines.length;
            if (licLine < licenceLines.length) {
                return licenceLines[licLine];
            } else {
                return "Line " + line + " is outside the skyLines. ";
            }

        } else {
            // return the sign line
            return signLines[line];
        }
    }

    @Override
    public int jump(Instant timestamp) {
        return 0;
    }

    @Override
    public void unfreezeBeforeBoundary() {
        // do nothing
    }

    @Override
    public void unfreezeAfterBoundary() {
        // do nothing
    }

    @Override
    public boolean cancelSearch() {
        return false;
    }

    @Override
    public LogStatistics getStatistics() {
        Instant now = Instant.now();
        return new LogStatistics(LogStatistics.Status.UNINITIALISED, 0, now, now);
    }

    @Override
    public Boundary getBeforeBoundary() {
        return beforeBoundary;
    }

    @Override
    public void setBeforeBoundary(Instant t) {
        // do nothing
    }

    @Override
    public Boundary getAfterBoundary() {
        return afterBoundary;
    }

    @Override
    public void setAfterBoundary(Instant t) {
        // do nothing
    }

    @Override
    public Boundary getNowBoundary() {
        return nowBoundary;
    }

    @Override
    public void setBoundaryExpansionListener(BoundaryExpansionListener listener) {
        // do nothing - Mood doesn't support boundary expansion
    }

    @Override
    public SegmentInfo getSegmentInfo() {
        // Mood is a placeholder/demo provider - return uninitialized segment info
        return SegmentInfo.uninitialized();
    }

    public String getHelpText() {
        return sign;
    }

    /**
     * Returns the line number that contains the "Press L to login" instruction.
     * Used to center this line on initial display with golden ratio positioning.
     */
    public int getLoginInstructionLine() {
        for (int i = 0; i < signLines.length; i++) {
            if (signLines[i].contains("Press L to login and select index.")) {
                return i;
            }
        }
        // Fallback to a reasonable default if not found
        return signLines.length / 2;
    }

    private static final String ansiShadow = """
             ██████╗ ██╗  ██╗ ██████╗  █████╗
            ██╔═████╗╚██╗██╔╝██╔═████╗██╔══██╗
            ██║██╔██║ ╚███╔╝ ██║██╔██║███████║
            ████╔╝██║ ██╔██╗ ████╔╝██║██╔══██║
            ╚██████╔╝██╔╝ ██╗╚██████╔╝██║  ██║
             ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝
            """;

    private static final String ansiRegular = """
             ██████  ██   ██  ██████   █████
            ██  ████  ██ ██  ██  ████ ██   ██
            ██ ██ ██   ███   ██ ██ ██ ███████
            ████  ██  ██ ██  ████  ██ ██   ██
             ██████  ██   ██  ██████  ██   ██
            """;

    private static final String dosRebel = """
                █████                   █████      █████████
              ███░░░███               ███░░░███   ███░░░░░███
             ███   ░░███ █████ █████ ███   ░░███ ░███    ░███
            ░███    ░███░░███ ░░███ ░███    ░███ ░███████████
            ░███    ░███ ░░░█████░  ░███    ░███ ░███░░░░░███
            ░░███   ███   ███░░░███ ░░███   ███  ░███    ░███
             ░░░█████░   █████ █████ ░░░█████░   █████   █████
               ░░░░░░   ░░░░░ ░░░░░    ░░░░░░   ░░░░░   ░░░░░
            """;

    private static final String electronic = """
              ▄▄▄▄▄▄▄▄▄  ▄       ▄  ▄▄▄▄▄▄▄▄▄   ▄▄▄▄▄▄▄▄▄▄▄
             ▐░░░░░░░░░▌▐░▌     ▐░▌▐░░░░░░░░░▌ ▐░░░░░░░░░░░▌
            ▐░█░█▀▀▀▀▀█░▌▐░▌   ▐░▌▐░█░█▀▀▀▀▀█░▌▐░█▀▀▀▀▀▀▀█░▌
            ▐░▌▐░▌    ▐░▌ ▐░▌ ▐░▌ ▐░▌▐░▌    ▐░▌▐░▌       ▐░▌
            ▐░▌ ▐░▌   ▐░▌  ▐░▐░▌  ▐░▌ ▐░▌   ▐░▌▐░█▄▄▄▄▄▄▄█░▌
            ▐░▌  ▐░▌  ▐░▌   ▐░▌   ▐░▌  ▐░▌  ▐░▌▐░░░░░░░░░░░▌
            ▐░▌   ▐░▌ ▐░▌  ▐░▌░▌  ▐░▌   ▐░▌ ▐░▌▐░█▀▀▀▀▀▀▀█░▌
            ▐░▌    ▐░▌▐░▌ ▐░▌ ▐░▌ ▐░▌    ▐░▌▐░▌▐░▌       ▐░▌
            ▐░█▄▄▄▄▄█░█░▌▐░▌   ▐░▌▐░█▄▄▄▄▄█░█░▌▐░▌       ▐░▌
             ▐░░░░░░░░░▌▐░▌     ▐░▌▐░░░░░░░░░▌ ▐░▌       ▐░▌
              ▀▀▀▀▀▀▀▀▀  ▀       ▀  ▀▀▀▀▀▀▀▀▀   ▀         ▀
            """;

    private static final String mono9 = """
              ▄▄▄▄          ▄▄▄▄    ▄▄
             ▄▀  ▀▄ ▄   ▄  ▄▀  ▀▄   ██
             █  ▄ █  █▄█   █  ▄ █  █  █
             █    █  ▄█▄   █    █  █▄▄█
              █▄▄█  ▄▀ ▀▄   █▄▄█  █    █
            """;

    private static final String pagga = """
            ░▄▀▄░█░█░▄▀▄░█▀█
            ░█/█░▄▀▄░█/█░█▀█
            ░░▀░░▀░▀░░▀░░▀░▀
            """;

    private static final String smblock = """
            ▞▀▖   ▞▀▖▞▀▖
            ▌▞▌▚▗▘▌▞▌▙▄▌
            ▛ ▌▗▚ ▛ ▌▌ ▌
            ▝▀ ▘ ▘▝▀ ▘ ▘
            """;

    private static final String bigMono12 = """
              ░████░              ░████░     ▒██▒
              ██████              ██████     ▓██▓
             ▒██  ██▒            ▒██  ██▒    ████
             ██▒  ▒██  ███  ███  ██▒  ▒██    ████
             ██    ██   ██▒▒██   ██    ██   ▒█▓▓█▒
             ██ ██ ██   ▒████▒   ██ ██ ██   ▓█▒▒█▓
             ██ ██ ██    ████    ██ ██ ██   ██  ██
             ██    ██    ▒██▒    ██    ██   ██████
             ██▒  ▒██    ████    ██▒  ▒██  ░██████░
             ▒██  ██▒   ▒████▒   ▒██  ██▒  ▒██  ██▒
              ██████    ██▒▒██    ██████   ███  ███
              ░████░   ███  ███   ░████░   ██▒  ▒██
            """;

    private static final String[] moods = {ansiShadow, ansiRegular, dosRebel, electronic, mono9, pagga, smblock,
            bigMono12};

    static String banner = """

            0x0A PAGER

            Press L to login and select index.

            Press G to go to a specific time.

            Scroll using arrow keys or page up/down, or mouse wheel.
            Shift while scrolling marks the lines.
            Ctrl-space toggles (sets) the mark.

            Ctrl-S to search. Repeat to go to the next match.
            Ctrl-R search backwards (earlier). Repeat to go to the previous match.

            Search next stacktrace using Ctrl-T.   Ctrl-shift-T to search backwards.

            Esc or Ctrl-G to cancel.

            F3 to go to the next match.
            Shift-F3 , to go to the previous match.

            Ctrl-Z to undo the last search.

            F4 to open the search overview.

            H for help.

            """;

    // random date from the list
    static Instant randomInstant() {
        int idx = (int) (Math.random() * dates.size());
        return Instant.parse(dates.get(idx));
    }

    private static final List<String> dates = Arrays.asList("1903-12-17T15:35:00Z", // Wright brothers first flight
            "1783-11-21T12:00:00Z", // Montgolfier brothers first hot air balloon flight
            "1932-05-20T12:00:00Z", // Amelia Earhart first solo transatlantic flight
            "1969-02-09T12:00:00Z", // Boeing 747 first flight
            "1969-03-02T12:00:00Z", // Concorde first flight
            "1969-07-16T13:32:00Z", // Apollo 11 launch
            "1957-10-04T19:28:34Z", // Sputnik 1 launch
            "1961-04-12T06:06:59Z", // Yuri Gagarin first human in space
            "1957-11-03T02:30:42Z", // Laika first animal in orbit
            "1969-07-20T20:17:00Z", // Apollo 11 moon landing
            "1947-09-09T20:45:00Z", // Chuck Yeager breaks sound barrier
            "1969-10-30T06:30:00Z", // Boeing 747 first commercial flight
            "1947-12-23T12:00:00Z", // First jet airliner flight (de Havilland Comet)
            "1960-05-16T12:00:00Z", // First flight of the Lockheed SR-71 Blackbird
            "1958-01-28T12:58:00Z", // First flight of the Boeing 707
            "1958-10-18T12:00:00Z", // First flight of the Douglas DC-8
            "1930-02-18T12:00:00Z", // Charles Lindbergh first solo nonstop transcontinental flight
            "1928-09-28T12:00:00Z", // First flight of the Graf Zeppelin
            "1981-04-12T12:00:00Z" // First flight of the Space Shuttle Columbia
    );
}
