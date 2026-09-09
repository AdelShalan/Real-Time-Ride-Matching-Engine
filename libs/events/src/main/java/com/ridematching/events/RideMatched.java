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
 * @param offerToken     the Redis claim's opaque token. Carried on the event because the
 *                       service that records the assignment is not the one that made the
 *                       claim, and releasing that claim later requires proving ownership of
 *                       it. Reconstructing the token from a naming convention would couple
 *                       two services to a string format neither of them declares.
 * @param distanceMeters straight-line distance from driver to pickup at match time
 * @param occurredAt     when the claim succeeded
 */
public record RideMatched(
        UUID eventId,
        UUID rideId,
        UUID driverId,
        long fenceToken,
        String offerToken,
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
