package com.ridematching.trip.application;

import com.ridematching.domain.trip.RideId;

/**
 * The trip changed between being read and being written.
 *
 * <p>Optimistic locking, not pessimistic: the contended window here is microseconds wide and
 * the collision rate is low, so taking a row lock for every read would cost more than the rare
 * retry it saves. What matters is that the collision is *detected* — a last-writer-wins update
 * would let a driver's acceptance silently overwrite a rider's cancellation, which is exactly
 * the class of bug this system exists to demonstrate the absence of.
 *
 * <p>Surfaces as {@code 409 Conflict} with a {@code Retry-After}. The caller re-reads and
 * decides again, because the right action genuinely depends on what the other writer did.
 */
public class ConcurrentTripModificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentTripModificationException(RideId rideId, int expectedVersion) {
        super("Trip %s was modified concurrently; expected version %d"
                .formatted(rideId, expectedVersion));
    }
}
