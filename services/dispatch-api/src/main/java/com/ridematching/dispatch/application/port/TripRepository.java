package com.ridematching.dispatch.application.port;

import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.DomainEvent;

import java.util.Optional;

/** Persistence port for the trip aggregate (ADR-0001). */
public interface TripRepository {

    /**
     * Inserts a newly requested trip along with its initial history entry, in one transaction.
     *
     * @throws com.ridematching.dispatch.application.DriverAlreadyAssignedException if the
     *         {@code uniq_driver_active_trip} index rejects the write — the structural
     *         guarantee from ADR-0004 firing
     */
    void insert(Trip trip);

    /**
     * Inserts a trip and records {@code event} for publication, atomically.
     *
     * <p>One transaction, two writes. This is the whole point of the outbox: there is no
     * ordering of "save" and "publish" that is safe when they are separate commits, so they
     * are made into one (ADR-0003).
     */
    void insertWithEvent(Trip trip, DomainEvent event);

    Optional<Trip> findById(RideId rideId);
}
