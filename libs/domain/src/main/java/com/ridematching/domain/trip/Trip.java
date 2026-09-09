package com.ridematching.domain.trip;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A ride from request through to completion — the aggregate that owns the trip lifecycle.
 *
 * <p>Every state change funnels through {@link #transitionTo}, which consults
 * {@link TripStatus#canTransitionTo} and throws rather than coercing. There is no setter for
 * {@code status}: it is impossible to put a Trip into an illegal state through this API, so
 * the invariant does not depend on callers remembering to check.
 *
 * <p>Plain Java by design (ADR-0001): no Spring, no JPA, no Jackson. The whole lifecycle is
 * unit-testable in milliseconds without a database, and persistence mapping happens in an
 * adapter that depends on this class rather than the other way round.
 *
 * <p>Not thread-safe, and deliberately so. Concurrency is handled where the durable state
 * lives — the Redis claim and the PostgreSQL unique index (ADR-0004) — not by locking an
 * in-memory object that only ever exists inside one request's scope.
 */
public final class Trip {

    private final RideId id;
    private final RiderId riderId;
    private final Coordinates pickup;
    private final Coordinates dropoff;
    private final VehicleClass vehicleClass;
    private final Instant requestedAt;
    private final Clock clock;
    private final List<TripTransition> history = new ArrayList<>();

    private TripStatus status;
    private DriverId driverId;
    private Long fenceToken;
    private String offerToken;
    private Instant matchedAt;
    private Instant completedAt;

    private Trip(RideId id,
                 RiderId riderId,
                 Coordinates pickup,
                 Coordinates dropoff,
                 VehicleClass vehicleClass,
                 Clock clock,
                 Instant requestedAt,
                 TripStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.riderId = Objects.requireNonNull(riderId, "riderId");
        this.pickup = Objects.requireNonNull(pickup, "pickup");
        this.dropoff = Objects.requireNonNull(dropoff, "dropoff");
        this.vehicleClass = Objects.requireNonNull(vehicleClass, "vehicleClass");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Creates a newly requested trip using the system clock. */
    public static Trip request(RideId id,
                               RiderId riderId,
                               Coordinates pickup,
                               Coordinates dropoff,
                               VehicleClass vehicleClass) {
        return request(id, riderId, pickup, dropoff, vehicleClass, Clock.systemUTC());
    }

    /**
     * Creates a newly requested trip against an explicit clock.
     *
     * <p>The clock is injected rather than calling {@code Instant.now()} inside the entity
     * so tests can assert on timestamps and on ordering deterministically. Time is an input
     * to this aggregate, not an ambient global.
     */
    public static Trip request(RideId id,
                               RiderId riderId,
                               Coordinates pickup,
                               Coordinates dropoff,
                               VehicleClass vehicleClass,
                               Clock clock) {
        Trip trip = new Trip(id, riderId, pickup, dropoff, vehicleClass, clock,
                clock.instant(), TripStatus.REQUESTED);
        trip.history.add(new TripTransition(null, TripStatus.REQUESTED, null,
                "rider requested a trip", trip.requestedAt));
        return trip;
    }

    /**
     * Rebuilds a trip from its persisted state.
     *
     * <p><strong>This is the one door into the aggregate that does not go through the
     * transition table</strong>, and it is for persistence adapters only. A trip loaded from
     * the database is already in whatever state it reached; replaying it forward through
     * {@code beginMatching -> offerTo -> accept} would invent history that did not happen and
     * would rewrite the timestamps that make the audit trail worth keeping.
     *
     * <p>What it does not skip is the invariant: a snapshot whose status holds a driver but
     * carries none is rejected in {@link TripSnapshot}'s constructor rather than loaded. The
     * guard that matters — "can this trip legally move to that state" — still applies to every
     * transition made after loading, which is the whole reason the aggregate is reconstructed
     * instead of the adapter updating columns directly.
     *
     * <p>History is not restored. The durable audit trail lives in {@code trip_events}, and
     * loading it on every state change would read rows nobody looks at to answer a question
     * ({@code what may this trip do next}) that only needs the current status. Transitions made
     * after rehydration append to a fresh list, and the adapter persists only those.
     */
    public static Trip rehydrate(TripSnapshot snapshot, Clock clock) {
        Objects.requireNonNull(snapshot, "snapshot");
        Trip trip = new Trip(snapshot.id(), snapshot.riderId(), snapshot.pickup(),
                snapshot.dropoff(), snapshot.vehicleClass(), clock,
                snapshot.requestedAt(), snapshot.status());

        trip.driverId = snapshot.driverId();
        trip.fenceToken = snapshot.fenceToken();
        trip.offerToken = snapshot.offerToken();
        trip.matchedAt = snapshot.matchedAt();
        trip.completedAt = snapshot.completedAt();
        return trip;
    }

    /** The current state, flattened for persistence. See {@link #rehydrate}. */
    public TripSnapshot snapshot() {
        return new TripSnapshot(id, riderId, pickup, dropoff, vehicleClass, status,
                driverId, fenceToken, offerToken, requestedAt, matchedAt, completedAt);
    }

    // ---------------------------------------------------------------- transitions

    /** The matching engine has picked the request up and begun searching. */
    public void beginMatching() {
        transitionTo(TripStatus.MATCHING, "matching engine consumed the request");
    }

    /**
     * A driver has been claimed and an offer sent.
     *
     * @param driver     the claimed driver
     * @param fenceToken monotonic token from the Redis claim; a later write carrying a
     *                   lower token is rejected, which is what makes an expired lease safe
     *                   (ADR-0004)
     * @param offerToken the claim's opaque token, retained because releasing the Redis claim
     *                   later requires proving ownership of it. A trip that holds a driver
     *                   without it could never hand that driver back except by TTL expiry.
     */
    public void offerTo(DriverId driver, long fenceToken, String offerToken) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(offerToken, "offerToken");
        if (fenceToken <= 0) {
            throw new IllegalArgumentException("Fence token must be positive, was " + fenceToken);
        }
        if (this.fenceToken != null && fenceToken <= this.fenceToken) {
            throw new IllegalArgumentException(
                    "Stale fence token %d; trip has already seen %d".formatted(fenceToken, this.fenceToken));
        }
        this.driverId = driver;
        this.fenceToken = fenceToken;
        this.offerToken = offerToken;
        transitionTo(TripStatus.OFFERED, "offer sent to driver " + driver);
    }

    /** The driver declined, or the offer TTL elapsed. Releases the driver and resumes search. */
    public void releaseOffer(String reason) {
        DriverId released = this.driverId;
        transitionTo(TripStatus.MATCHING, reason);
        // Cleared only after the transition succeeds, so a rejected transition leaves the
        // trip exactly as it was.
        this.driverId = null;
        recordDriverRelease(released, reason);
    }

    /** The driver accepted the offer and is en route. */
    public void accept() {
        transitionTo(TripStatus.ACCEPTED, "driver accepted the offer");
        this.matchedAt = clock.instant();
    }

    /** The rider is in the vehicle. */
    public void startTrip() {
        transitionTo(TripStatus.IN_PROGRESS, "rider picked up");
    }

    /** The rider has been dropped off. */
    public void complete() {
        transitionTo(TripStatus.COMPLETED, "rider dropped off");
        this.completedAt = clock.instant();
    }

    /** No driver could be found within the SLA window. */
    public void markUnmatched(String reason) {
        transitionTo(TripStatus.UNMATCHED, reason);
        this.driverId = null;
    }

    /** Called off before the journey began. Illegal once IN_PROGRESS — see {@link TripStatus}. */
    public void cancel(String reason) {
        transitionTo(TripStatus.CANCELLED, reason);
        this.driverId = null;
    }

    private void transitionTo(TripStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalTripTransitionException(id, status, target);
        }
        if (target.holdsDriver() && driverId == null) {
            throw new IllegalStateException(
                    "Trip %s cannot enter %s without an assigned driver".formatted(id, target));
        }
        TripStatus previous = status;
        status = target;
        history.add(new TripTransition(previous, target, driverId, reason, clock.instant()));
    }

    private void recordDriverRelease(DriverId released, String reason) {
        if (released != null) {
            history.add(new TripTransition(TripStatus.OFFERED, TripStatus.MATCHING, released,
                    "released driver " + released + ": " + reason, clock.instant()));
        }
    }

    // ---------------------------------------------------------------- accessors

    public RideId id() {
        return id;
    }

    public RiderId riderId() {
        return riderId;
    }

    public TripStatus status() {
        return status;
    }

    public Coordinates pickup() {
        return pickup;
    }

    public Coordinates dropoff() {
        return dropoff;
    }

    public VehicleClass vehicleClass() {
        return vehicleClass;
    }

    public Optional<DriverId> assignedDriver() {
        return Optional.ofNullable(driverId);
    }

    public Optional<Long> fenceToken() {
        return Optional.ofNullable(fenceToken);
    }

    /**
     * The Redis claim token for the current driver, if one is held.
     *
     * <p>Read this <em>before</em> a transition that gives the driver up ({@link #cancel},
     * {@link #markUnmatched}, {@link #releaseOffer}): those clear the assignment, and the
     * token is what the claim store requires to release it.
     */
    public Optional<String> offerToken() {
        return Optional.ofNullable(offerToken);
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Optional<Instant> matchedAt() {
        return Optional.ofNullable(matchedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    /** Append-only audit trail, oldest first. */
    public List<TripTransition> history() {
        return Collections.unmodifiableList(history);
    }

    /** Straight-line distance between pickup and dropoff. Not a routed distance. */
    public double directDistanceMeters() {
        return pickup.distanceMetersTo(dropoff);
    }

    @Override
    public boolean equals(Object o) {
        // Entity identity: two Trip instances are the same trip if they share an id,
        // regardless of how their mutable state has diverged.
        if (this == o) {
            return true;
        }
        return o instanceof Trip other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Trip[id=%s, status=%s, driver=%s]".formatted(id, status, driverId);
    }
}
