package com.ridematching.trip.application.port;

import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.DomainEvent;

import java.util.Optional;

/**
 * Persistence port for the trip aggregate as a mutable, long-lived thing (ADR-0001).
 *
 * <p>Distinct from dispatch-api's {@code TripRepository}, which only ever inserts: that
 * service creates a trip and hands it off, and giving it an update method it does not use
 * would invite one. This port reads a trip back and writes it forward, and every write is
 * version-checked.
 *
 * <p><strong>The version is part of the port, not an implementation detail.</strong> A trip is
 * modified by two entirely different callers — the matching engine's event lands on it at the
 * same moment a rider may be cancelling — and a port that let a caller write without saying
 * what it had read would make lost updates invisible. Handing the version back on load and
 * requiring it on save turns that race into a rejected write.
 */
public interface TripStore {

    /** A trip and the version it was read at. */
    record Versioned(Trip trip, int version) {
    }

    Optional<Versioned> load(RideId rideId);

    /**
     * Writes the trip's current state, its new history entries, and {@code event}, in one
     * transaction.
     *
     * <p>The event goes to the outbox rather than to Kafka, so the state change and its
     * announcement commit together or not at all (ADR-0003). Pass null when a transition has
     * no downstream meaning — {@code accept} and {@code start} are real state changes with no
     * consumer today, and publishing events nobody reads is a topic to maintain for nothing.
     *
     * @param expectedVersion the version returned by {@link #load}
     * @throws com.ridematching.trip.application.ConcurrentTripModificationException if the row
     *         has moved on since it was read
     * @throws com.ridematching.trip.application.DriverAlreadyAssignedException if
     *         {@code uniq_driver_active_trip} rejects the write — another trip already holds
     *         this driver (ADR-0004)
     */
    void save(Trip trip, int expectedVersion, DomainEvent event);
}
