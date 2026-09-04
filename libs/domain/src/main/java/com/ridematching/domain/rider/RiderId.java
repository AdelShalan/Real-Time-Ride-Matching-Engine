package com.ridematching.domain.rider;

import java.util.UUID;

/**
 * Identifier for a rider — the passenger requesting a trip.
 *
 * <p>Wrapped rather than passed as a bare {@code UUID} so the compiler rejects the classic
 * mix-up of handing a driver id where a rider id is expected, which is invisible at a call
 * site taking several UUIDs in a row.
 */
public record RiderId(UUID value) {

    public RiderId {
        if (value == null) {
            throw new IllegalArgumentException("RiderId must not be null");
        }
    }

    public static RiderId newId() {
        return new RiderId(UUID.randomUUID());
    }

    public static RiderId of(String value) {
        return new RiderId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
