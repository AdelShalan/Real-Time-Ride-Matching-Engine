package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A trip was called off before it began.
 *
 * @param eventId    unique per event instance
 * @param rideId     the ride, and the partition key
 * @param driverId   the driver who had been assigned, or null if none had been yet. Both are
 *                   ordinary cases: a rider can cancel while still in MATCHING, and a
 *                   consumer that assumes a driver is always present will break the first
 *                   time someone changes their mind quickly.
 * @param reason     why, as supplied by whoever cancelled
 * @param occurredAt when the cancellation was recorded
 */
public record RideCancelled(
        UUID eventId,
        UUID rideId,
        UUID driverId,
        String reason,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_CANCELLED;
    }
}
