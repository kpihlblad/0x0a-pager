package se.grynna.pager.splunk;

import java.time.Instant;
import java.util.List;

public record LoadedLogLines(Instant start, Instant end, List<LoadedLogLine> logLines) {

    @Override
    public String toString() {
        return "LoadedLogLines{" + "start=" + start + ", end=" + end + ", logLinesCount=" + logLines.size() + '}';
    }

    double averageVelocity() {
        double seconds = (end.toEpochMilli() - start.toEpochMilli()) / 1000.0;

        if (seconds <= 0) {
            return 0.0;
        }
        return (logLines.size() / seconds);
    }

    double rangedVelocity() {
        if (logLines.isEmpty()) {
            return 0.0;
        }
        if (logLines.size() == 1) {
            return averageVelocity();
        }

        Instant firstLogTime = logLines.getFirst().getSplunkTime();
        Instant lastLogTime = logLines.getLast().getSplunkTime();
        double seconds = (lastLogTime.toEpochMilli() - firstLogTime.toEpochMilli()) / 1000.0;

        if (seconds <= 0) {
            return 0.0;
        }
        return (logLines.size() / seconds);
    }
}
