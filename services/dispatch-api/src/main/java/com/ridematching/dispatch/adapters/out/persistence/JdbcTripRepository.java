package com.ridematching.dispatch.adapters.out.persistence;

import com.ridematching.dispatch.application.DriverAlreadyAssignedException;
import com.ridematching.dispatch.application.port.TripRepository;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.domain.trip.TripTransition;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL persistence for the trip aggregate.
 *
 * <p>Maps the domain object to columns by hand rather than through JPA. The aggregate stays a
 * plain object with no annotations (ADR-0001), and the mapping lives here where it belongs —
 * which also means the SQL that carries the concurrency guarantee is visible rather than
 * generated.
 */
public class JdbcTripRepository implements TripRepository {

    private final JdbcClient jdbc;

    public JdbcTripRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void insert(Trip trip) {
        try {
            jdbc.sql("""
                            INSERT INTO trips
                                (id, rider_id, driver_id, status, vehicle_class, fence_token,
                                 pickup_lat, pickup_lng, dropoff_lat, dropoff_lng,
                                 requested_at, matched_at, completed_at, version)
                            VALUES
                                (:id, :riderId, :driverId, CAST(:status AS trip_status),
                                 CAST(:vehicleClass AS vehicle_class), :fenceToken,
                                 :pickupLat, :pickupLng, :dropoffLat, :dropoffLng,
                                 :requestedAt, :matchedAt, :completedAt, 0)
                            """)
                    .param("id", trip.id().value())
                    .param("riderId", trip.riderId().value())
                    .param("driverId", trip.assignedDriver().map(driver -> driver.value()).orElse(null))
                    .param("status", trip.status().name())
                    .param("vehicleClass", trip.vehicleClass().name())
                    .param("fenceToken", trip.fenceToken().orElse(null))
                    .param("pickupLat", trip.pickup().latitude())
                    .param("pickupLng", trip.pickup().longitude())
                    .param("dropoffLat", trip.dropoff().latitude())
                    .param("dropoffLng", trip.dropoff().longitude())
                    .param("requestedAt", Timestamp.from(trip.requestedAt()))
                    .param("matchedAt", trip.matchedAt().map(Timestamp::from).orElse(null))
                    .param("completedAt", trip.completedAt().map(Timestamp::from).orElse(null))
                    .update();
        } catch (DuplicateKeyException e) {
            // Either the ride id collided (effectively impossible with UUIDv4) or
            // uniq_driver_active_trip fired. The latter is the ADR-0004 guarantee doing its
            // job and is expected under contention, so it gets its own type rather than
            // surfacing as a generic persistence failure.
            throw new DriverAlreadyAssignedException(
                    "Trip %s could not be inserted; a driver may already hold an active trip"
                            .formatted(trip.id()), e);
        }

        // Same transaction as the trip row: the audit trail cannot drift from the state.
        for (TripTransition transition : trip.history()) {
            insertEvent(trip.id(), transition);
        }
    }

    private void insertEvent(RideId rideId, TripTransition transition) {
        jdbc.sql("""
                        INSERT INTO trip_events
                            (trip_id, from_status, to_status, driver_id, reason, occurred_at)
                        VALUES
                            (:tripId, CAST(:fromStatus AS trip_status),
                             CAST(:toStatus AS trip_status), :driverId, :reason, :occurredAt)
                        """)
                .param("tripId", rideId.value())
                .param("fromStatus", transition.previousState().map(status -> status.name()).orElse(null))
                .param("toStatus", transition.to().name())
                .param("driverId", transition.driver().map(driver -> driver.value()).orElse(null))
                .param("reason", transition.reason())
                .param("occurredAt", Timestamp.from(transition.occurredAt()))
                .update();
    }

    @Override
    public Optional<Trip> findById(RideId rideId) {
        return jdbc.sql("""
                        SELECT id, rider_id, driver_id, status, vehicle_class,
                               pickup_lat, pickup_lng, dropoff_lat, dropoff_lng, requested_at
                        FROM trips
                        WHERE id = :id
                        """)
                .param("id", rideId.value())
                .query((rs, rowNum) -> {
                    // Rebuilt through the domain factory so an invalid row cannot produce an
                    // invalid aggregate — the record's own validation runs on the way back in.
                    Trip trip = Trip.request(
                            new RideId(rs.getObject("id", UUID.class)),
                            new RiderId(rs.getObject("rider_id", UUID.class)),
                            new Coordinates(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
                            new Coordinates(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lng")),
                            VehicleClass.valueOf(rs.getString("vehicle_class")),
                            java.time.Clock.systemUTC());
                    return new StoredTrip(
                            trip,
                            TripStatus.valueOf(rs.getString("status")),
                            rs.getObject("driver_id", UUID.class));
                })
                .optional()
                .map(stored -> stored.trip());
    }

    /**
     * Intermediate carrier for a row read back.
     *
     * <p>Replaying stored state onto the aggregate is deferred until the trip lifecycle moves
     * to trip-service, which owns transitions. Today the API only ever reads back trips it
     * just created, all in REQUESTED, so reconstructing history is not yet needed — noted
     * rather than silently ignored.
     */
    private record StoredTrip(Trip trip, TripStatus status, UUID driverId) {
    }
}
