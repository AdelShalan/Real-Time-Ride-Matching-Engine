package com.ridematching.domain.driver;

import java.util.UUID;

/**
 * Identifier for a driver.
 *
 * <p>Wrapped rather than passed as a bare {@code UUID} so the compiler rejects handing a
 * rider id where a driver id is expected.
 *
 * <p>This is the id the whole concurrency design turns on: it is the Redis claim key, the
 * subject of the {@code uniq_driver_active_trip} unique partial index in PostgreSQL, and
 * the Kafka partition key for location telemetry (ADR-0004, ADR-0003).
 */
public record DriverId(UUID value) {

    public DriverId {
        if (value == null) {
            throw new IllegalArgumentException("DriverId must not be null");
        }
    }

    public static DriverId newId() {
        return new DriverId(UUID.randomUUID());
    }

    public static DriverId of(String value) {
        return new DriverId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
