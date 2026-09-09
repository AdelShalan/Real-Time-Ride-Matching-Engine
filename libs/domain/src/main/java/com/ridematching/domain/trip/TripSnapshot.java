package com.ridematching.domain.trip;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;

import java.time.Instant;
import java.util.Objects;

/**
 * A trip's persisted state, flattened.
 *
 * <p>Exists so a persistence adapter has exactly one thing to map to and from, rather than
 * reaching into the aggregate field by field. {@link Trip#snapshot()} produces one and
 * {@link Trip#rehydrate} consumes one, so adding a field to the aggregate breaks both
 * directions at compile time instead of silently dropping a column on the way back in.
 *
 * <p>Deliberately not the aggregate itself: a record with public components has no invariants
 * and no transition guard, which is right for a row and wrong for a trip. Everything that
 * enforces the state machine stays in {@link Trip}.
 *
 * @param offerToken token from the Redis claim that reserved {@code driverId}. Needed to
 *                   release that claim later, and the reason it is stored rather than derived:
 *                   the matching engine's naming convention is that service's business, and a
 *                   second service reconstructing the string would couple the two to a format
 *                   nobody declared.
 */
public record TripSnapshot(
        RideId id,
        RiderId riderId,
        Coordinates pickup,
        Coordinates dropoff,
        VehicleClass vehicleClass,
        TripStatus status,
        DriverId driverId,
        Long fenceToken,
        String offerToken,
        Instant requestedAt,
        Instant matchedAt,
        Instant completedAt) {

    public TripSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(riderId, "riderId");
        Objects.requireNonNull(pickup, "pickup");
        Objects.requireNonNull(dropoff, "dropoff");
        Objects.requireNonNull(vehicleClass, "vehicleClass");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");

        // The same invariant the trips_driver_required_when_held CHECK constraint enforces in
        // PostgreSQL, applied on the way out of the database as well as on the way in. A row
        // that violates it is corrupt, and loading it into an aggregate would spread the
        // corruption rather than surface it.
        if (status.holdsDriver() && driverId == null) {
            throw new IllegalArgumentException(
                    "Trip %s is %s but has no driver".formatted(id, status));
        }
    }
}
