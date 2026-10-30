package se.grynna.pager.export;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Writes export files. The caller (on the EDT) hands over the events already chosen; encoding and
 * writing run on the background executor, and the outcome goes back through the foreground executor
 * to the notice or failure sink (the EDT in the app).
 */
@Slf4j
public final class ExportController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss");

    /** Writes the finished content to a file; {@link Files#write} in production. */
    @FunctionalInterface
    public interface FileWriter {
        void write(Path file, byte[] content) throws IOException;
    }

    private final Executor background;
    private final Executor foreground;
    private final FileWriter writer;
    private final Consumer<String> notice;
    private final Consumer<String> failure;

    public ExportController(Executor background, Executor foreground, Consumer<String> notice,
            Consumer<String> failure) {
        this(background, foreground, Files::write, notice, failure);
    }

    public ExportController(Executor background, Executor foreground, FileWriter writer, Consumer<String> notice,
            Consumer<String> failure) {
        this.background = background;
        this.foreground = foreground;
        this.writer = writer;
        this.notice = notice;
        this.failure = failure;
    }

    /** A single daemon thread for the export writes, so they never stall the EDT. */
    public static Executor newBackgroundExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pager-export");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Export {@code events} in {@code format} to {@code file}. Nothing is written for an empty list.
     * Must be called on the EDT.
     */
    public void export(List<ExportEvent> events, ExportFormat format, ExportOptions options, Path file) {
        export(events, format, options, file, null);
    }

    /**
     * As {@link #export(List, ExportFormat, ExportOptions, Path)}; {@code onSuccess} (may be null) runs on
     * the foreground executor after a successful write, and never after a failure or an empty list.
     */
    public void export(List<ExportEvent> events, ExportFormat format, ExportOptions options, Path file,
            Runnable onSuccess) {
        if (events.isEmpty()) {
            notice.accept(ExportNotices.NOTHING_TO_EXPORT);
            return;
        }
        int count = events.size();
        background.execute(() -> {
            try {
                writer.write(file, EventExporter.export(events, format, options));
            } catch (IOException | RuntimeException e) {
                log.warn("Export to {} failed", file, e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                foreground.execute(() -> failure.accept(ExportNotices.exportFailed(reason)));
                return;
            }
            String text = ExportNotices.exported(count, String.valueOf(file.getFileName()));
            foreground.execute(() -> {
                notice.accept(text);
                if (onSuccess != null) {
                    onSuccess.run();
                }
            });
        });
    }

    /** The suggested file name: {@code 0x0a-pager-<index>-<yyyyMMdd-HHmmss>.<ext>}, the time in {@code zone}. */
    public static String defaultFileName(String index, ExportFormat format, Instant now, ZoneId zone) {
        String name = index == null || index.isBlank() ? "log" : index.replaceAll("[^A-Za-z0-9._+-]", "_");
        return "0x0a-pager-" + name + "-" + STAMP.withZone(zone).format(now) + format.fileExtension();
    }
}
