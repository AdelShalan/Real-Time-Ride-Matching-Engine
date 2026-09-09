package com.ridematching.trip.adapters.in.rest;

import com.ridematching.domain.trip.Trip;

import java.util.UUID;

/**
 * What a caller gets back after moving a trip, or reading one.
 *
 * @param rideId   the trip
 * @param status   the state it is now in — the caller's confirmation that the transition it
 *                 asked for is the one that happened
 * @param driverId who holds it, or null once a terminal state has released them
 */
public record TripResponseDto(UUID rideId, String status, UUID driverId) {

    public static TripResponseDto of(Trip trip) {
        return new TripResponseDto(
                trip.id().value(),
                trip.status().name(),
                trip.assignedDriver().map(driver -> driver.value()).orElse(null));
    }
}
