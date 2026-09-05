package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * No driver could be claimed inside the SLA window.
 *
 * <p>Published rather than silently dropped: a rider waiting on a ride that never gets matched
 * needs to be told, and the rate of these is the clearest signal of supply shortfall.
 *
 * @param eventId    unique per event instance
 * @param rideId     the ride, and the partition key
 * @param reason     why matching gave up
 * @param occurredAt when it gave up
 */
public record RideUnmatched(
        UUID eventId,
        UUID rideId,
        String reason,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_UNMATCHED;
    }
}
