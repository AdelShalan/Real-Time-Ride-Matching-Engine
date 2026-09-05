package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A rider asked for a trip. The work item the matching engine consumes.
 *
 * @param eventId    unique per event instance; consumers dedupe on it
 * @param rideId     the ride, and the partition key
 * @param riderId    who asked
 * @param pickupLat  pickup latitude
 * @param pickupLng  pickup longitude
 * @param dropoffLat dropoff latitude
 * @param dropoffLng dropoff longitude
 * @param vehicleClass requested tier
 * @param occurredAt when the request was accepted
 */
public record RideRequested(
        UUID eventId,
        UUID rideId,
        UUID riderId,
        double pickupLat,
        double pickupLng,
        double dropoffLat,
        double dropoffLng,
        String vehicleClass,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        // Keying on rideId is what guarantees requested -> matched -> completed can never be
        // observed out of order for one ride.
        return rideId.toString();
    }

    @Override
    public String topic() {
        return Topics.RIDE_REQUESTED;
    }
}
