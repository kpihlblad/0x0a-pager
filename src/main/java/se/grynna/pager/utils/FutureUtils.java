package se.grynna.pager.utils;

import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.exceptions.TemporarySearchException;

import java.util.concurrent.*;

public class FutureUtils {
    public static <T> CompletableFuture<T> supplyAsync(SearchSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (TemporarySearchException | SearchResultToBigException | SearchException e) {
                throw new CompletionException(e); // Wraps the checked exception for propagation
            }
        });
    }
}
