package com.ridematching.dispatch.adapters.in.rest;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Wire format for POST /v1/rides.
 *
 * <p>Separate from the domain types on purpose (ADR-0001): validation annotations and JSON
 * concerns belong at the edge, not on {@code Coordinates}.
 */
public record RideRequestDto(
        @NotNull UUID riderId,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double pickupLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double pickupLng,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double dropoffLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double dropoffLng,
        String vehicleClass) {
}
