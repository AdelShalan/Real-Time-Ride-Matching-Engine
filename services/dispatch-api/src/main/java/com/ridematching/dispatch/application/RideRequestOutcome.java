package com.ridematching.dispatch.application;

import com.ridematching.domain.trip.RideId;

/**
 * Result of a ride request, mapping one-to-one onto the ADR-0005 response table.
 *
 * <p>A sealed-style enum of outcomes rather than exceptions for the non-error paths: a retry
 * arriving mid-flight is ordinary behaviour, not an exceptional condition, and modelling it
 * as a return value keeps it out of stack traces and error dashboards.
 */
public record RideRequestOutcome(Kind kind, RideId rideId, Integer replayStatus, String replayBody) {

    public enum Kind {
        /** First request with this key: work enqueued. HTTP 202. */
        ACCEPTED,
        /** Retry while the original is still running. HTTP 409 + Retry-After. */
        IN_PROGRESS,
        /** Retry after completion: replay the stored response verbatim. */
        REPLAYED,
        /** Same key, different body. HTTP 422 — a client bug. */
        KEY_REUSE
    }

    public static RideRequestOutcome accepted(RideId rideId) {
        return new RideRequestOutcome(Kind.ACCEPTED, rideId, null, null);
    }

    public static RideRequestOutcome inProgress() {
        return new RideRequestOutcome(Kind.IN_PROGRESS, null, null, null);
    }

    public static RideRequestOutcome replay(RideId rideId, Integer status, String body) {
        return new RideRequestOutcome(Kind.REPLAYED, rideId, status, body);
    }

    public static RideRequestOutcome keyReuse() {
        return new RideRequestOutcome(Kind.KEY_REUSE, null, null, null);
    }
}
