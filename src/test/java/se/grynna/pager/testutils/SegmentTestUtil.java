package se.grynna.pager.testutils;

import se.grynna.pager.splunk.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static se.grynna.pager.splunk.LoadedLogLine.MsgType.RAW;

public class SegmentTestUtil {

    // Helper to create a segment with specific data
    public static LogSegment createTestSegment(Instant startTime, Instant endTime, int startLine, int size) {
        var s = new LogSegment(startTime, endTime, startLine, size);
        //        var decision = SegmentTestUtil.makeLoadDecision(startTime, endTime);
        //        var lines = SegmentTestUtil.makeLoadedLines(size, startTime, endTime);
        //        s.init(startTime, decision, lines, true);
        return s;
    }

    // Helper to create a LoadedLogLines with dummy entries evenly spread between start and end
    public static LoadedLogLines makeLoadedLines(int count, Instant start, Instant end) {
        List<LoadedLogLine> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant t;
            if (count <= 1) {
                t = start;
            } else {
                long startMs = start.toEpochMilli();
                long endMs = end.toEpochMilli();
                long ts = startMs + (endMs - startMs) * i / (count - 1);
                t = Instant.ofEpochMilli(ts);
            }
            lines.add(new LoadedLogLine(RAW, "seg", 0, "cb", "bkt", t, "raw", t, t, "msg" + i, false, Level.INFO));
        }
        return new LoadedLogLines(start, end, lines);
    }

    public static LoadDecision makeLoadDecision(int direction, Instant start, Instant end) {
        return LoadDecision.load(null, direction, start, end, "test");
    }
}
