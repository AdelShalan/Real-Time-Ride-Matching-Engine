package com.ridematching.trip.application;

import com.ridematching.domain.trip.RideId;

/** No trip with the requested id. Surfaces as {@code 404}. */
public class TripNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TripNotFoundException(RideId rideId) {
        super("No trip with id " + rideId);
    }
}
