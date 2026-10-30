package se.grynna.pager.splunk;

/**
 * Persisted by ordinal in the log store rows (see {@code LoadedLogLineSerializer}). Append new constants at the end
 * only; reordering, inserting or removing one requires bumping {@code RowCodec.FORMAT_VERSION}.
 */
public enum Level {
    ERROR, WARNING, INFO
}
