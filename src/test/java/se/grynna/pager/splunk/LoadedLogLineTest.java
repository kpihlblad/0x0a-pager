package se.grynna.pager.splunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.RAW;

class LoadedLogLineTest {
    @org.junit.jupiter.api.Test
    public void equalsShouldReturnTrueForIdenticalLoadedLogLines() {
        LoadedLogLine logLine1 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1",
                Instant.now(), Instant.now(), "message1", false, Level.INFO);
        LoadedLogLine logLine2 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", logLine1.getTime(), "raw1",
                logLine1.getIndextime(), logLine1.getMsgTime(), "message1", false, Level.INFO);

        assertTrue(logLine1.equals(logLine2));
    }

    @org.junit.jupiter.api.Test
    public void equalsShouldReturnFalseForDifferentLoadedLogLines() {
        LoadedLogLine logLine1 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1",
                Instant.now(), Instant.now(), "message1", false, Level.INFO);
        LoadedLogLine logLine2 = new LoadedLogLine(RAW, "segment2", 2, "cb2", "bkt2", Instant.now(), "raw2",
                Instant.now(), Instant.now(), "message2", true, Level.ERROR);

        assertFalse(logLine1.equals(logLine2));
    }

    @org.junit.jupiter.api.Test
    public void hashCodeShouldBeConsistentForSameLoadedLogLine() {
        LoadedLogLine logLine = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1",
                Instant.now(), Instant.now(), "message1", false, Level.INFO);

        int hashCode1 = logLine.hashCode();
        int hashCode2 = logLine.hashCode();

        assertEquals(hashCode1, hashCode2);
    }

    @org.junit.jupiter.api.Test
    public void equalInstancesShouldHaveEqualHashCodes() {
        Instant time = Instant.parse("2023-05-01T12:00:00Z");

        LoadedLogLine logLine1 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", time, "raw1", time.plusSeconds(1),
                time.plusSeconds(2), "message1", false, Level.INFO);
        LoadedLogLine logLine2 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", time, "raw1", time.plusSeconds(1),
                time.plusSeconds(2), "message1", false, Level.INFO);

        assertNotSame(logLine1, logLine2);
        assertEquals(logLine1, logLine2);
        assertEquals(logLine1.hashCode(), logLine2.hashCode());
    }

    @org.junit.jupiter.api.Test
    public void equalInstancesShouldDedupeInHashSet() {
        Instant time = Instant.parse("2023-05-01T12:00:00Z");

        LoadedLogLine logLine1 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", time, "raw1", time.plusSeconds(1),
                time.plusSeconds(2), "message1", false, Level.INFO);
        LoadedLogLine logLine2 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", time, "raw1", time.plusSeconds(1),
                time.plusSeconds(2), "message1", false, Level.INFO);

        HashSet<LoadedLogLine> set = new HashSet<>();
        set.add(logLine1);
        set.add(logLine2);

        assertEquals(1, set.size());
        assertTrue(set.contains(logLine2));
    }

    @org.junit.jupiter.api.Test
    public void compareShouldReturnZeroForIdenticalLoadedLogLines() {
        LoadedLogLine logLine1 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1",
                Instant.now(), Instant.now(), "message1", false, Level.INFO);
        LoadedLogLine logLine2 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", logLine1.getTime(), "raw1",
                logLine1.getIndextime(), logLine1.getMsgTime(), "message1", false, Level.INFO);

        assertEquals(0, logLine1.compare(logLine1, logLine2));
    }

    @org.junit.jupiter.api.Test
    public void compareShouldReturnNegativeForSmallerLoadedLogLine() {
        LoadedLogLine logLine1 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1",
                Instant.now(), Instant.now(), "message1", false, Level.INFO);
        LoadedLogLine logLine2 = new LoadedLogLine(RAW, "segment2", 2, "cb2", "bkt2", Instant.now().plusSeconds(10),
                "raw2", Instant.now().plusSeconds(10), Instant.now().plusSeconds(10), "message2", true, Level.ERROR);

        assertTrue(logLine1.compare(logLine1, logLine2) < 0);
    }

    @org.junit.jupiter.api.Test
    public void compareShouldReturnPositiveForLargerLoadedLogLine() {
        LoadedLogLine logLine1 = new LoadedLogLine(RAW, "segment2", 2, "cb2", "bkt2", Instant.now().plusSeconds(10),
                "raw2", Instant.now().plusSeconds(10), Instant.now().plusSeconds(10), "message2", true, Level.ERROR);
        LoadedLogLine logLine2 = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1",
                Instant.now(), Instant.now(), "message1", false, Level.INFO);

        assertTrue(logLine1.compare(logLine1, logLine2) > 0);
    }

    @org.junit.jupiter.api.Test
    public void toStringShouldReturnFormattedString() {
        LoadedLogLine logLine = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(),
                "{\"key\":\"value\"}", Instant.now(), Instant.now(), "message1", false, Level.INFO);

        String result = logLine.toString();

        assertTrue(result.contains("segmentId    : segment1"));
        assertTrue(result.contains("lineNumber   : 1"));
        assertTrue(result.contains("bkt          : bkt1"));
        assertTrue(result.contains("cb           : cb1"));
        assertTrue(result.contains("\"key\" : \"value\""));
    }

    @org.junit.jupiter.api.Test
    public void activitySurvivesJavaSerialization() throws Exception {
        LoadedLogLine line = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1", Instant.now(),
                Instant.now(), "message1", false, Level.INFO);
        line.setActivity(new Activity("host-a", "pod-1", "thread-2", null, "idx:src"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(line);
        }
        LoadedLogLine restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (LoadedLogLine) in.readObject();
        }

        assertThat(restored.getActivity()).isEqualTo(new Activity("host-a", "pod-1", "thread-2", null, "idx:src"));
    }

    @org.junit.jupiter.api.Test
    public void resolvedActivityIsExtractedLazilyWhenMissing() {
        LoadedLogLine line = new LoadedLogLine(LoadedLogLine.MsgType.JSON, "segment1", 1, "cb1", "bkt1",
                Instant.now(), "{\"host\":\"node-7\",\"msg\":\"hello\"}", Instant.now(), Instant.now(), "hello",
                false, Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cb1")));
        assertThat(line.getActivity()).isNull();

        Activity resolved = line.resolvedActivity();

        assertThat(resolved).isNotNull();
        assertThat(resolved).isEqualTo(ActivityExtractor.extract(line.getEventMap(),
                LoadedLogLine.parseObjectOrNull(line.getRaw()), "hello", line.getRaw()));
        assertThat(line.getActivity()).isSameAs(resolved);
    }

    @org.junit.jupiter.api.Test
    public void resolvedActivityReturnsStoredActivityWithoutRecomputing() {
        LoadedLogLine line = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), "raw1", Instant.now(),
                Instant.now(), "message1", false, Level.INFO);
        Activity stored = new Activity("stored", null, null, null, null);
        line.setActivity(stored);

        assertThat(line.resolvedActivity()).isSameAs(stored);
    }

    @org.junit.jupiter.api.Test
    public void resolvedActivityHandlesRawWithoutEventMap() {
        LoadedLogLine line = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", Instant.now(), null, Instant.now(),
                Instant.now(), null, false, Level.INFO);

        assertThat(line.resolvedActivity()).isNotNull();
    }

    @org.junit.jupiter.api.Test
    public void equalsAndHashCodeIgnoreActivity() {
        Instant time = Instant.parse("2023-05-01T12:00:00Z");
        LoadedLogLine withActivity = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", time, "raw1", time, time,
                "message1", false, Level.INFO);
        LoadedLogLine without = new LoadedLogLine(RAW, "segment1", 1, "cb1", "bkt1", time, "raw1", time, time,
                "message1", false, Level.INFO);
        withActivity.setActivity(new Activity("host", "pod", "thread", null, null));

        assertEquals(without, withActivity);
        assertEquals(without.hashCode(), withActivity.hashCode());
    }
}
