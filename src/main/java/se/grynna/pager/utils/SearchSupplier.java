package se.grynna.pager.utils;

import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.exceptions.TemporarySearchException;

@FunctionalInterface
public interface SearchSupplier<T> {
    T get() throws TemporarySearchException, SearchResultToBigException, SearchException; // Or narrow to your specific checked exception type if needed
}
