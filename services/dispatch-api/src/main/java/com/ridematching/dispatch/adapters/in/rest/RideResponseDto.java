package com.ridematching.dispatch.adapters.in.rest;

import java.util.UUID;

/**
 * Response for a created or replayed ride request.
 *
 * @param rideId  identifier the client polls with
 * @param status  trip status at the time of the response
 * @param pollUrl where to check progress; matching is asynchronous
 */
public record RideResponseDto(UUID rideId, String status, String pollUrl) {

    public static RideResponseDto accepted(UUID rideId) {
        return new RideResponseDto(rideId, "REQUESTED", "/v1/rides/" + rideId);
    }
}
