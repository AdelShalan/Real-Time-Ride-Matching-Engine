package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A driver was claimed for a ride. Fans out to notification and billing.
 *
 * @param eventId        unique per event instance
 * @param rideId         the ride, and the partition key
 * @param driverId       the claimed driver
 * @param fenceToken     the claim's fencing token (ADR-0004)
 * @param distanceMeters straight-line distance from driver to pickup at match time
 * @param occurredAt     when the claim succeeded
 */
public record RideMatched(
        UUID eventId,
        UUID rideId,
        UUID driverId,
        long fenceToken,
        double distanceMeters,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_MATCHED;
    }
}
