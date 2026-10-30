package se.grynna.pager.export;

import java.time.ZoneId;
import java.util.Objects;

/**
 * @param highlightTerm the search term to mark in HTML output (case-insensitive); null or empty marks nothing
 * @param zone          the zone used for the time column of CSV and HTML output
 */
public record ExportOptions(String highlightTerm, ZoneId zone) {

    public ExportOptions {
        Objects.requireNonNull(zone, "zone");
    }
}
