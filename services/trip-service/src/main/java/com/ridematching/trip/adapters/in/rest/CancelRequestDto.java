package com.ridematching.trip.adapters.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a trip is being called off.
 *
 * <p>Required rather than optional. The reason lands in the append-only {@code trip_events}
 * trail and on the {@code ride.cancelled.v1} event, and a cancellation with no reason is a row
 * that answers no question anyone later asks of it.
 */
public record CancelRequestDto(
        @NotBlank(message = "a cancellation reason is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String reason) {
}
