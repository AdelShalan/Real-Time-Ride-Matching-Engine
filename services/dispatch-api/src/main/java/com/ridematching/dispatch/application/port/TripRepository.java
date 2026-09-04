package com.ridematching.dispatch.application.port;

import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;

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

    Optional<Trip> findById(RideId rideId);
}
