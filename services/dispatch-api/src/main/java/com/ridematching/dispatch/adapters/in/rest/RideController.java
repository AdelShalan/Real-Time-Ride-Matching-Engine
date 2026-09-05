package com.ridematching.dispatch.adapters.in.rest;

import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.RideRequestOutcome;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Ride request intake.
 *
 * <p>Returns {@code 202 Accepted} rather than blocking until a driver is found. That is the
 * key latency decision in the system: the API's p99 stays in the tens of milliseconds no
 * matter how long matching takes, and a slow dispatch can never turn into request timeouts.
 */
@RestController
@RequestMapping("/v1/rides")
public class RideController {

    private final RequestRideUseCase requestRide;
    private final IdempotencyStore idempotency;
    private final ObjectMapper objectMapper;

    public RideController(RequestRideUseCase requestRide,
                          IdempotencyStore idempotency,
                          ObjectMapper objectMapper) {
        this.requestRide = requestRide;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> requestRide(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RideRequestDto request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST,
                    "Missing Idempotency-Key",
                    "Ride creation requires a client-generated Idempotency-Key header."));
        }

        var command = new RequestRideUseCase.RequestRideCommand(
                new RiderId(request.riderId()),
                new Coordinates(request.pickupLat(), request.pickupLng()),
                new Coordinates(request.dropoffLat(), request.dropoffLng()),
                parseVehicleClass(request.vehicleClass()));

        RideRequestOutcome outcome = requestRide.handle(command, idempotencyKey);

        return switch (outcome.kind()) {
            case ACCEPTED -> accepted(outcome.rideId(), idempotencyKey);

            // The original is still running. Retry-After tells the client to wait rather than
            // hammer, which is what turns a retry storm into a queue.
            case IN_PROGRESS -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .header("Retry-After", "1")
                    .body(problem(HttpStatus.CONFLICT, "Request in progress",
                            "A request with this Idempotency-Key is still being processed."));

            // Replay the stored response verbatim — the client gets the same rideId it would
            // have got the first time, which is what it actually needs to continue.
            case REPLAYED -> ResponseEntity.status(HttpStatus.OK)
                    .body(RideResponseDto.accepted(outcome.rideId().value()));

            case KEY_REUSE -> ResponseEntity.unprocessableContent()
                    .body(problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused",
                            "This Idempotency-Key was already used for a different request."));
        };
    }

    private ResponseEntity<RideResponseDto> accepted(RideId rideId, String idempotencyKey) {
        RideResponseDto body = RideResponseDto.accepted(rideId.value());

        // Store the response before returning it, so a retry that arrives a millisecond later
        // replays exactly what this caller received.
        idempotency.complete(idempotencyKey, rideId.value(), HttpStatus.ACCEPTED.value(),
                objectMapper.writeValueAsString(body));

        return ResponseEntity.accepted()
                .header("Location", body.pollUrl())
                .body(body);
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<?> getRide(@PathVariable UUID rideId) {
        return requestRide.findById(new RideId(rideId))
                .<ResponseEntity<?>>map(trip -> ResponseEntity.ok(
                        new RideResponseDto(trip.id().value(), trip.status().name(),
                                "/v1/rides/" + trip.id())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(problem(HttpStatus.NOT_FOUND, "Ride not found",
                                "No ride with id " + rideId)));
    }

    private VehicleClass parseVehicleClass(String raw) {
        if (raw == null || raw.isBlank()) {
            return VehicleClass.STANDARD;
        }
        try {
            return VehicleClass.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown vehicleClass: " + raw);
        }
    }

    // Exception handling lives in RestExceptionHandler so every endpoint answers alike and a
    // new controller does not need handlers copied into it.

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
