package com.ridematching.domain.trip;

/**
 * Thrown when a trip is asked to move to a state it cannot legally reach.
 *
 * <p>These are genuinely exceptional — a duplicate Kafka delivery, a late offer response
 * racing an acceptance, or a bug. Adapters translate this into a {@code 409 Conflict}
 * rather than a {@code 500}: the request was well-formed, the trip was simply not in a
 * state where it made sense.
 */
public class IllegalTripTransitionException extends RuntimeException {

    /**
     * Declared explicitly rather than left to the compiler. These exceptions are never
     * serialised across a wire today, but an implicit value changes whenever the class
     * shape changes, which turns a harmless refactor into a deserialisation failure the
     * day something does serialise one.
     */
    private static final long serialVersionUID = 1L;

    private final TripStatus from;
    private final TripStatus to;

    public IllegalTripTransitionException(RideId rideId, TripStatus from, TripStatus to) {
        super("Trip %s cannot move from %s to %s; allowed from %s: %s"
                .formatted(rideId, from, to, from, from.allowedTargets()));
        this.from = from;
        this.to = to;
    }

    public TripStatus from() {
        return from;
    }

    public TripStatus to() {
        return to;
    }
}
