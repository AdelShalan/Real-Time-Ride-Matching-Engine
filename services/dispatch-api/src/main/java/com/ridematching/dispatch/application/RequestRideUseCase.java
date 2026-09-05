package com.ridematching.dispatch.application;

import com.ridematching.dispatch.application.port.IdempotencyRecord;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.dispatch.application.port.OutboxWriter;
import com.ridematching.dispatch.application.port.TripRepository;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.RideRequested;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Creates a ride request, exactly once per idempotency key (ADR-0005).
 *
 * <p>The API is deliberately thin: it validates, persists, and returns. It performs no
 * matching, so its latency is predictable and independent of how long dispatch takes. That is
 * what keeps the p99 API budget (120ms) separate from the p99 match budget (500ms).
 *
 * <p>Plain constructor injection, no Spring annotations — the whole algorithm is unit-testable
 * against fakes, including the concurrent-retry race.
 */
public class RequestRideUseCase {

    private final TripRepository trips;
    private final IdempotencyStore idempotency;
    private final OutboxWriter outbox;
    private final Clock clock;

    private final Counter created;
    private final Counter replayed;
    private final Counter inFlightConflicts;
    private final Counter keyReuse;

    public RequestRideUseCase(TripRepository trips,
                              IdempotencyStore idempotency,
                              OutboxWriter outbox,
                              MeterRegistry meters,
                              Clock clock) {
        this.trips = trips;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.clock = clock;

        this.created = Counter.builder("rides.requested")
                .description("Ride requests that created a new trip").register(meters);
        this.replayed = Counter.builder("rides.idempotent.replayed")
                .description("Retries served from a stored response").register(meters);
        this.inFlightConflicts = Counter.builder("rides.idempotent.in_flight")
                .description("Retries that arrived while the original was still running").register(meters);
        this.keyReuse = Counter.builder("rides.idempotent.key_reuse")
                .description("Same key presented with a different request body").register(meters);
    }

    /**
     * Handles one ride request.
     *
     * @param command      what the rider asked for
     * @param idempotencyKey client-generated key; required
     */
    public RideRequestOutcome handle(RequestRideCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        String requestHash = hash(command);

        // One atomic statement decides the winner. Everything below is reacting to that
        // decision, never re-checking it.
        if (idempotency.tryClaim(idempotencyKey, requestHash)) {
            return createTrip(command, idempotencyKey);
        }

        Optional<IdempotencyRecord> existing = idempotency.find(idempotencyKey);
        if (existing.isEmpty()) {
            // The key was claimed and then expired or was released between the two calls.
            // Rare, and safe to treat as a fresh attempt.
            return idempotency.tryClaim(idempotencyKey, requestHash)
                    ? createTrip(command, idempotencyKey)
                    : RideRequestOutcome.inProgress();
        }

        IdempotencyRecord record = existing.get();

        if (!record.matches(requestHash)) {
            // The same key for a different request is a client bug. Replaying the first
            // response would silently discard this request; failing loudly is kinder.
            keyReuse.increment();
            return RideRequestOutcome.keyReuse();
        }

        if (record.inProgress()) {
            inFlightConflicts.increment();
            return RideRequestOutcome.inProgress();
        }

        replayed.increment();
        return RideRequestOutcome.replay(
                new RideId(record.rideId()),
                record.responseStatus(),
                record.body().orElse(null));
    }

    /** Reads a trip back. The controller depends on this layer, never on the repository. */
    public Optional<Trip> findById(RideId rideId) {
        return trips.findById(rideId);
    }

    private RideRequestOutcome createTrip(RequestRideCommand command, String idempotencyKey) {
        RideId rideId = RideId.newId();
        try {
            Trip trip = Trip.request(
                    rideId,
                    command.riderId(),
                    command.pickup(),
                    command.dropoff(),
                    command.vehicleClass(),
                    clock);

            // Trip row and outbox row commit together (ADR-0003). Publishing to Kafka here
            // instead would be the dual-write bug: a crash between the two would leave either
            // an event for a trip that does not exist, or a trip nobody is told about.
            trips.insertWithEvent(trip, new RideRequested(
                    java.util.UUID.randomUUID(),
                    rideId.value(),
                    command.riderId().value(),
                    command.pickup().latitude(),
                    command.pickup().longitude(),
                    command.dropoff().latitude(),
                    command.dropoff().longitude(),
                    command.vehicleClass().name(),
                    clock.instant()));
            created.increment();
            return RideRequestOutcome.accepted(rideId);
        } catch (RuntimeException e) {
            // Release the claim so the client's retry is a genuine retry rather than a
            // permanent 409. Without this, one transient database error would poison that
            // key for its whole 24-hour lifetime.
            idempotency.release(idempotencyKey);
            throw e;
        }
    }

    /**
     * SHA-256 over the semantically meaningful fields.
     *
     * <p>Hashing the parsed command rather than the raw bytes means whitespace and JSON field
     * ordering do not change the hash — two byte-different encodings of the same request are
     * correctly treated as the same request.
     */
    private String hash(RequestRideCommand command) {
        String canonical = "%s|%s,%s|%s,%s|%s".formatted(
                command.riderId(),
                command.pickup().latitude(), command.pickup().longitude(),
                command.dropoff().latitude(), command.dropoff().longitude(),
                command.vehicleClass());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }

    /** What the rider asked for, after parsing and validation. */
    public record RequestRideCommand(
            RiderId riderId,
            Coordinates pickup,
            Coordinates dropoff,
            VehicleClass vehicleClass) {
    }
}
