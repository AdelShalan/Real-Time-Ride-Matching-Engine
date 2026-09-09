package com.ridematching.trip.adapters.in.rest;

import com.ridematching.domain.trip.RideId;
import com.ridematching.trip.application.TripLifecycle;
import com.ridematching.trip.application.TripNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The trip lifecycle over HTTP.
 *
 * <p>Four transitions, each its own endpoint rather than a {@code PATCH} that takes a target
 * status. The difference matters: {@code POST /accept} can only ever mean one transition, so
 * the set of legal requests is the set of legal transitions, and an invalid move is rejected
 * by routing rather than by validating a string against the state machine. It also keeps the
 * URLs honest about being actions, which is what they are — completing a trip is not an edit
 * to a status field.
 *
 * <p>Synchronous, unlike ride creation. Ride requests return {@code 202} because matching takes
 * as long as it takes and nobody should hold a socket open for it; these transitions are a
 * single row update and the caller genuinely needs to know whether it won. A driver tapping
 * accept must be told immediately if someone else got there first.
 *
 * <p>No idempotency keys here, deliberately. The state machine already provides the property
 * they would buy: a repeated {@code accept} on an {@code ACCEPTED} trip is rejected by the
 * transition table, not applied twice. Adding a key store would be machinery guarding an
 * invariant that is enforced a layer down.
 */
@RestController
@RequestMapping("/v1/trips")
public class TripController {

    private final TripLifecycle lifecycle;

    public TripController(TripLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** The driver accepted the offer. {@code OFFERED -> ACCEPTED}. */
    @PostMapping("/{rideId}/accept")
    public ResponseEntity<TripResponseDto> accept(@PathVariable UUID rideId) {
        lifecycle.accept(new RideId(rideId));
        return current(rideId);
    }

    /** The rider is in the vehicle. {@code ACCEPTED -> IN_PROGRESS}. */
    @PostMapping("/{rideId}/start")
    public ResponseEntity<TripResponseDto> start(@PathVariable UUID rideId) {
        lifecycle.start(new RideId(rideId));
        return current(rideId);
    }

    /** The rider was dropped off. {@code IN_PROGRESS -> COMPLETED}, and the driver is freed. */
    @PostMapping("/{rideId}/complete")
    public ResponseEntity<TripResponseDto> complete(@PathVariable UUID rideId) {
        lifecycle.complete(new RideId(rideId));
        return current(rideId);
    }

    /**
     * The trip was called off. Terminal, and the driver is freed.
     *
     * <p>Rejected once the trip is {@code IN_PROGRESS} — see {@code TripStatus}. That comes
     * back as a 409 naming the state, not a silent no-op.
     */
    @PostMapping("/{rideId}/cancel")
    public ResponseEntity<TripResponseDto> cancel(@PathVariable UUID rideId,
                                                  @Valid @RequestBody CancelRequestDto request) {
        lifecycle.cancel(new RideId(rideId), request.reason());
        return current(rideId);
    }

    @GetMapping("/{rideId}")
    public ResponseEntity<TripResponseDto> get(@PathVariable UUID rideId) {
        return current(rideId);
    }

    /**
     * Reads the trip back after the transition.
     *
     * <p>A second read rather than returning the in-memory aggregate. It costs a round trip on
     * an already-committed request and buys the guarantee that the response describes what is
     * actually stored — including anything a concurrent writer did in between, which is
     * precisely what a caller checking the result is asking about.
     */
    private ResponseEntity<TripResponseDto> current(UUID rideId) {
        RideId id = new RideId(rideId);
        return lifecycle.find(id)
                .map(trip -> ResponseEntity.ok(TripResponseDto.of(trip)))
                .orElseThrow(() -> new TripNotFoundException(id));
    }
}
