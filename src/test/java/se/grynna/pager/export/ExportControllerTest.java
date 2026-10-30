package se.grynna.pager.export;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class ExportControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-25T10:15:30Z");
    private static final ExportOptions OPTIONS = new ExportOptions(null, ZoneOffset.UTC);

    private final List<String> notices = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private List<ExportEvent> events;

    @TempDir
    Path dir;

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
        events = List.of(event(0, "a, \"quoted\""), event(1, "b"));
    }

    private static ExportEvent event(long seconds, String message) {
        Instant time = T0.plusSeconds(seconds);
        var line = new LoadedLogLine(MsgType.JSON, null, 0, "cd" + seconds, "bkt", time, "raw", time, time, message,
                false, Level.WARNING);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cd" + seconds, "_bkt", "bkt")));
        return ExportEvent.of(line);
    }

    private ExportController direct(ExportController.FileWriter writer) {
        return new ExportController(Runnable::run, Runnable::run, writer, notices::add, failures::add);
    }

    private ExportController direct() {
        return new ExportController(Runnable::run, Runnable::run, notices::add, failures::add);
    }

    @Test
    void onSuccessRunsAfterTheNoticeOnlyWhenTheWriteSucceeded() {
        List<String> order = new ArrayList<>();
        ExportController ok = new ExportController(Runnable::run, Runnable::run, (file, bytes) -> {},
                text -> order.add("notice"), failures::add);
        ok.export(events, ExportFormat.TEXT, OPTIONS, dir.resolve("a.txt"), () -> order.add("success"));
        assertThat(order).containsExactly("notice", "success");

        List<String> failed = new ArrayList<>();
        ExportController bad = new ExportController(Runnable::run, Runnable::run, (file, bytes) -> {
            throw new IOException("disk full");
        }, text -> failed.add("notice"), text -> failed.add("failure"));
        bad.export(events, ExportFormat.TEXT, OPTIONS, dir.resolve("b.txt"), () -> failed.add("success"));
        assertThat(failed).containsExactly("failure");
    }

    @Test
    void onSuccessDoesNotRunForAnEmptyExport() {
        List<String> ran = new ArrayList<>();
        direct().export(List.of(), ExportFormat.TEXT, OPTIONS, dir.resolve("c.txt"), () -> ran.add("success"));
        assertThat(ran).isEmpty();
    }

    @Test
    void csvIsWrittenWithAByteOrderMarkAndTheNoticeNamesTheFile() throws IOException {
        Path file = dir.resolve("out.csv");

        direct().export(events, ExportFormat.CSV, OPTIONS, file);

        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(bytes).isEqualTo(EventExporter.export(events, ExportFormat.CSV, OPTIONS));
        assertThat(notices).containsExactly("Exported 2 events to out.csv");
        assertThat(failures).isEmpty();
    }

    @Test
    void textFormatsAreWrittenWithoutAByteOrderMark() throws IOException {
        Path file = dir.resolve("out.jsonl");

        direct().export(events, ExportFormat.JSONL, OPTIONS, file);

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).isEqualTo(EventExporter.exportText(events, ExportFormat.JSONL, OPTIONS));
        assertThat(written).doesNotStartWith("\uFEFF");
        assertThat(notices).containsExactly("Exported 2 events to out.jsonl");
    }

    @Test
    void singleEventIsCountedInTheSingular() throws IOException {
        direct().export(List.of(event(0, "only")), ExportFormat.TEXT, OPTIONS, dir.resolve("one.txt"));

        assertThat(notices).containsExactly("Exported 1 event to one.txt");
    }

    @Test
    void aWriteFailureGoesToTheFailureSinkNotTheNotice() {
        ExportController.FileWriter failing = (file, content) -> {
            throw new IOException("disk full");
        };

        direct(failing).export(events, ExportFormat.CSV, OPTIONS, dir.resolve("out.csv"));

        assertThat(failures).containsExactly("Export failed: disk full");
        assertThat(notices).isEmpty();
    }

    @Test
    void anEmptySelectionWritesNothingAndSaysSo() {
        List<Path> written = new ArrayList<>();

        direct((file, content) -> written.add(file)).export(List.of(), ExportFormat.CSV, OPTIONS,
                dir.resolve("out.csv"));

        assertThat(written).isEmpty();
        assertThat(notices).containsExactly("Nothing to export");
        assertThat(failures).isEmpty();
    }

    @Test
    void theWriteRunsOnTheBackgroundExecutorAndTheOutcomeComesBackOnTheForeground() {
        List<Runnable> background = new ArrayList<>();
        List<Runnable> foreground = new ArrayList<>();
        List<Thread> writerThreads = new ArrayList<>();
        Executor queue = background::add;
        var controller = new ExportController(queue, foreground::add, (file, content) -> {
            writerThreads.add(Thread.currentThread());
        }, notices::add, failures::add);

        controller.export(events, ExportFormat.TEXT, OPTIONS, dir.resolve("queued.txt"));

        assertThat(background).hasSize(1);
        assertThat(writerThreads).isEmpty();
        assertThat(notices).isEmpty();

        background.forEach(Runnable::run);

        assertThat(writerThreads).containsExactly(Thread.currentThread());
        assertThat(notices).isEmpty();
        assertThat(foreground).hasSize(1);
        foreground.forEach(Runnable::run);
        assertThat(notices).containsExactly("Exported 2 events to queued.txt");
    }

    @Test
    void defaultFileNameCarriesTheIndexTheStampAndTheExtension() {
        assertThat(ExportController.defaultFileName("web", ExportFormat.CSV, T0, ZoneOffset.UTC))
                .isEqualTo("0x0a-pager-web-20261025-101530.csv");
        assertThat(ExportController.defaultFileName("web logs/x", ExportFormat.HTML, T0, ZoneOffset.UTC))
                .isEqualTo("0x0a-pager-web_logs_x-20261025-101530.html");
        assertThat(ExportController.defaultFileName(" ", ExportFormat.RAW, T0, ZoneOffset.UTC))
                .isEqualTo("0x0a-pager-log-20261025-101530.log");
    }

    @Test
    void defaultFileNameKeepsThePlusOfSeveralIndexes() {
        assertThat(ExportController.defaultFileName("a+b", ExportFormat.CSV, T0, ZoneOffset.UTC))
                .isEqualTo("0x0a-pager-a+b-20261025-101530.csv");
        assertThat(ExportController.defaultFileName("all", ExportFormat.CSV, T0, ZoneOffset.UTC))
                .isEqualTo("0x0a-pager-all-20261025-101530.csv");
    }

    @Test
    void theStampIsInTheGivenZone() {
        assertThat(ExportController.defaultFileName("web", ExportFormat.TEXT, T0,
                ZoneOffset.ofHours(2))).isEqualTo("0x0a-pager-web-20261025-121530.txt");
    }
}
