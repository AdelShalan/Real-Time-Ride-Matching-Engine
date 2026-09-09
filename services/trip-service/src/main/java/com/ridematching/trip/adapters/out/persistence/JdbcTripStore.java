package com.ridematching.trip.adapters.out.persistence;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripSnapshot;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.domain.trip.TripTransition;
import com.ridematching.events.DomainEvent;
import com.ridematching.platform.outbox.OutboxWriter;
import com.ridematching.trip.application.ConcurrentTripModificationException;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.port.TripStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL persistence for the trip lifecycle.
 *
 * <p>Hand-mapped rather than JPA, for the same reason the rest of the system is: the SQL that
 * carries the concurrency guarantee should be readable, not generated. Nothing here is
 * incidental — the {@code WHERE version} clause and the {@code DuplicateKeyException} branch
 * are both load-bearing.
 */
public class JdbcTripStore implements TripStore {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final Clock clock;

    public JdbcTripStore(JdbcClient jdbc, OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public Optional<Versioned> load(RideId rideId) {
        return jdbc.sql("""
                        SELECT id, rider_id, driver_id, status, vehicle_class, fence_token,
                               offer_token, pickup_lat, pickup_lng, dropoff_lat, dropoff_lng,
                               requested_at, matched_at, completed_at, version
                        FROM trips
                        WHERE id = :id
                        """)
                .param("id", rideId.value())
                .query((rs, rowNum) -> new Versioned(
                        Trip.rehydrate(toSnapshot(rs), clock), rs.getInt("version")))
                .optional();
    }

    @Override
    @Transactional
    public void save(Trip trip, int expectedVersion, DomainEvent event) {
        TripSnapshot state = trip.snapshot();
        int updated;
        try {
            updated = jdbc.sql("""
                            UPDATE trips SET
                                driver_id    = :driverId,
                                status       = CAST(:status AS trip_status),
                                fence_token  = :fenceToken,
                                offer_token  = :offerToken,
                                matched_at   = :matchedAt,
                                completed_at = :completedAt,
                                version      = version + 1
                            WHERE id = :id AND version = :expectedVersion
                            """)
                    .param("driverId", state.driverId() == null ? null : state.driverId().value())
                    .param("status", state.status().name())
                    .param("fenceToken", state.fenceToken())
                    .param("offerToken", state.offerToken())
                    .param("matchedAt", timestamp(state.matchedAt()))
                    .param("completedAt", timestamp(state.completedAt()))
                    .param("id", state.id().value())
                    .param("expectedVersion", expectedVersion)
                    .update();
        } catch (DuplicateKeyException e) {
            // uniq_driver_active_trip: another trip already holds this driver. The ADR-0004
            // guarantee firing, and the only place in this service where it can.
            throw new DriverAlreadyAssignedException(
                    "Driver %s already holds an active trip".formatted(state.driverId()), e);
        }

        if (updated == 0) {
            // Zero rows means the version moved, not that the trip vanished: the caller read
            // it moments ago inside this same use case. Distinguishing the two with an extra
            // SELECT would buy a better error message and a second round trip on the contended
            // path, which is the wrong trade.
            throw new ConcurrentTripModificationException(state.id(), expectedVersion);
        }

        // Only transitions made since the trip was rehydrated. Trip.rehydrate deliberately
        // does not restore history, so this appends the new entries rather than re-inserting
        // the whole audit trail on every state change.
        for (TripTransition transition : trip.history()) {
            insertEvent(state.id(), transition);
        }

        if (event != null) {
            // Same transaction as the state change. That is the whole point (ADR-0003).
            outbox.append(event);
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
                .param("fromStatus", transition.previousState().map(Enum::name).orElse(null))
                .param("toStatus", transition.to().name())
                .param("driverId", transition.driver().map(DriverId::value).orElse(null))
                .param("reason", transition.reason())
                .param("occurredAt", Timestamp.from(transition.occurredAt()))
                .update();
    }

    private static TripSnapshot toSnapshot(ResultSet rs) throws SQLException {
        UUID driverId = rs.getObject("driver_id", UUID.class);

        // getLong returns 0 for SQL NULL, and 0 is not a valid fence token — the domain
        // rejects it. wasNull() is the only way to tell the two apart, and it reports on the
        // most recent read, so it has to be consumed here rather than further down among the
        // constructor arguments where a later column would have overwritten the answer.
        long fence = rs.getLong("fence_token");
        Long fenceToken = rs.wasNull() ? null : fence;

        return new TripSnapshot(
                new RideId(rs.getObject("id", UUID.class)),
                new RiderId(rs.getObject("rider_id", UUID.class)),
                new Coordinates(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
                new Coordinates(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lng")),
                VehicleClass.valueOf(rs.getString("vehicle_class")),
                TripStatus.valueOf(rs.getString("status")),
                driverId == null ? null : new DriverId(driverId),
                fenceToken,
                rs.getString("offer_token"),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("matched_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
