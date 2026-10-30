package se.grynna.pager.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

// cancellable CompletableFuture

@Slf4j
public class CancellableCompletableFuture<T> extends CompletableFuture<T> {
    private final Runnable cancel;

    public CancellableCompletableFuture(Runnable cancel) {
        this.cancel = cancel;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        try {
            // Cancel the Splunk job
            cancel.run();
            log.debug("Cancelled successfully");
            return super.cancel(mayInterruptIfRunning);
        } catch (Exception e) {
            log.error("Failed to cancel job", e);
            this.completeExceptionally(e);
            return false;
        }
    }
}
