package se.grynna.pager.ui;

import se.grynna.pager.splunk.BoundaryType;

import java.time.Instant;

/**
 * Represents a request to expand a boundary (BEFORE or AFTER).
 * The reply consumer is called with the type, acceptance status, and the new boundary if accepted.
 */
public record BoundaryRequest(BoundaryType type, Instant suggestedBoundary, BoundaryReplyConsumer reply) {

    @FunctionalInterface
    public interface BoundaryReplyConsumer {
        void accept(BoundaryType type, boolean accepted, Instant newBoundary);
    }

    public void accept(boolean accepted) {
        reply.accept(type, accepted, accepted ? suggestedBoundary : null);
    }
}
