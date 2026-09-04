package com.ridematching.domain.trip;

import com.ridematching.domain.driver.DriverId;

import java.time.Instant;
import java.util.Optional;

/**
 * One recorded state change, appended to the trip's history and never modified.
 *
 * <p>This is the in-memory form of the {@code trip_events} table: an append-only audit
 * trail that answers "why is this trip in this state?" without inference. When a rider
 * disputes a fare or a driver disputes an assignment, the sequence of transitions with
 * their reasons is the record.
 *
 * @param from      state left behind; null for the initial transition into REQUESTED
 * @param to        state entered
 * @param driverId  driver involved, if any
 * @param reason    short human-readable cause, e.g. "offer expired", "rider cancelled"
 * @param occurredAt when the change happened
 */
public record TripTransition(
        TripStatus from,
        TripStatus to,
        DriverId driverId,
        String reason,
        Instant occurredAt) {

    public TripTransition {
        if (to == null) {
            throw new IllegalArgumentException("Transition target must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("Transition timestamp must not be null");
        }
    }

    public Optional<DriverId> driver() {
        return Optional.ofNullable(driverId);
    }

    public Optional<TripStatus> previousState() {
        return Optional.ofNullable(from);
    }
}
