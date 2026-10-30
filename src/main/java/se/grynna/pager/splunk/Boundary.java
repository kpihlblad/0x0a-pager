package se.grynna.pager.splunk;

import java.time.Instant;
import java.util.Optional;

public record Boundary(BoundaryType bt, Instant instant, Optional<Integer> line) {

    public Boundary(BoundaryType type, Instant t, int i) {
        this(type, t, Optional.of(i));
    }

    public Boundary(BoundaryType bt, Instant t) {
        this(bt, t, Optional.empty());
    }

    @Override
    public String toString() {

        var str = line.isPresent() ? Integer.toString(line.get()) : "-";

        return bt + " " + instant + "(" + str + ")";
    }
}
