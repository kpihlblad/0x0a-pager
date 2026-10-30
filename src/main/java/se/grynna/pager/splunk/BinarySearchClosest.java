package se.grynna.pager.splunk;

import java.time.Duration;
import java.util.Comparator;

/**
 * Generic binary search algorithm that finds the closest match when no exact match exists.
 *
 * @param <T> The type of elements being searched
 */
public class BinarySearchClosest<T> {

    /**
     * Functional interface for providing elements by index.
     * This allows the search to work with any data source without coupling to specific implementations.
     *
     * @param <T> The type of elements to provide
     */
    @FunctionalInterface
    public interface ElementProvider<T> {
        /**
         * Get element at the specified index.
         *
         * @param index The index of the element
         * @return The element at the given index
         * @throws Exception if element cannot be retrieved
         */
        T getElement(int index) throws Exception;
    }

    /**
     * Functional interface for calculating distance between a search key and an element.
     *
     * @param <T> The type of elements
     */
    @FunctionalInterface
    public interface DistanceCalculator<T> {
        /**
         * Calculate the distance between a search key and an element.
         * Lower values indicate closer matches.
         *
         * @param key The search key
         * @param element The element to compare against
         * @return The non-negative distance (smaller = closer)
         */
        Duration calculateDistance(T key, T element);
    }

    private final ElementProvider<T> provider;
    private final Comparator<T> comparator;
    private final DistanceCalculator<T> distanceCalculator;

    /**
     * Create a binary search instance with element provider and comparator.
     *
     * @param provider Function to retrieve elements by index
     * @param comparator Comparator for ordering elements
     * @param distanceCalculator Calculator for finding closest match
     */
    public BinarySearchClosest(ElementProvider<T> provider, Comparator<T> comparator,
            DistanceCalculator<T> distanceCalculator) {
        this.provider = provider;
        this.comparator = comparator;
        this.distanceCalculator = distanceCalculator;
    }

    /**
     * Search for the element closest to the given key within the specified range.
     * Returns the index of the exact match if found, otherwise returns the index of the closest element.
     *
     * @param key The search key
     * @param low The lower bound of the search range (inclusive)
     * @param high The upper bound of the search range (inclusive)
     * @return The index of the matching or closest element
     * @throws Exception if element retrieval fails
     */
    public int search(T key, int low, int high) throws Exception {
        int closestIndex = low;
        Duration minDistance = null;

        while (low <= high) {
            int mid = low + ((high - low) / 2);
            T element = provider.getElement(mid);

            // Calculate distance for closest match tracking
            Duration distance = distanceCalculator.calculateDistance(key, element);
            if (minDistance == null || distance.compareTo(minDistance) < 0) {
                minDistance = distance;
                closestIndex = mid;
            }

            // Standard binary search comparison
            int comparison = comparator.compare(element, key);

            if (comparison < 0) {
                // Element is less than key, search right half
                low = mid + 1;
            } else if (comparison > 0) {
                // Element is greater than key, search left half
                high = mid - 1;
            } else {
                // Exact match found
                return mid;
            }
        }

        // No exact match, return closest
        return closestIndex;
    }
}
