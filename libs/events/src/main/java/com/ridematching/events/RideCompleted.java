package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A trip finished normally. The driver has been handed back to the pool.
 *
 * <p>Billing's trigger: this is the first event in the stream that means a fare is owed.
 * {@link RideMatched} says a driver was assigned, which is not the same thing — an assignment
 * that ends in a cancellation bills nothing.
 *
 * @param eventId    unique per event instance
 * @param rideId     the ride, and the partition key
 * @param driverId   who drove it
 * @param distanceMeters straight-line pickup-to-dropoff distance. Not a routed distance, and
 *                       therefore not a fare: a real platform bills on the actual path driven.
 *                       Named for what it is rather than dressed up as something it is not.
 * @param occurredAt when the rider was dropped off
 */
public record RideCompleted(
        UUID eventId,
        UUID rideId,
        UUID driverId,
        double distanceMeters,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_COMPLETED;
    }
}
