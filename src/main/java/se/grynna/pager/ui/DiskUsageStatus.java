package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Periodic disk-usage status update. The (possibly slow) size calculation runs on the
 * calling background thread; only the label update is posted to the EDT.
 */
@Slf4j
final class DiskUsageStatus implements Runnable {

    private final Supplier<String> sizeProvider;
    private final Consumer<String> label;
    private final Executor edt;

    DiskUsageStatus(Supplier<String> sizeProvider, Consumer<String> label, Executor edt) {
        this.sizeProvider = sizeProvider;
        this.label = label;
        this.edt = edt;
    }

    @Override
    public void run() {
        try {
            String text;
            try {
                text = sizeProvider.get();
            } catch (Exception e) {
                log.error("Error updating base directory size", e);
                text = "-";
            }
            String result = text;
            edt.execute(() -> label.accept(result));
        } catch (Throwable t) {
            // A throwing run must not silently cancel subsequent scheduled runs.
            log.error("Error in periodic disk usage update", t);
        }
    }
}
