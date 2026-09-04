package com.ridematching.domain.trip;

import java.util.UUID;

/**
 * Identifier for a single ride request and the trip it becomes.
 *
 * <p>Wrapped rather than passed as a bare {@code UUID} so the compiler rejects the
 * classic mix-up of handing a rider id where a ride id is expected — a bug that is
 * invisible at a call site taking three UUIDs in a row.
 *
 * <p>Doubles as the Kafka partition key for every ride event, which is what guarantees
 * {@code requested -> matched -> completed} can never be observed out of order (ADR-0003).
 */
public record RideId(UUID value) {

    public RideId {
        if (value == null) {
            throw new IllegalArgumentException("RideId must not be null");
        }
    }

    public static RideId newId() {
        return new RideId(UUID.randomUUID());
    }

    public static RideId of(String value) {
        return new RideId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
