package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.events.DomainEvent;
import com.ridematching.events.RideCancelled;
import com.ridematching.events.RideCompleted;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.trip.application.port.TripStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Moves a trip through its states, and hands the driver back when it is done.
 *
 * <p>Every method here is the same four steps: load, transition, persist, then reconcile
 * Redis. The order is the interesting part.
 *
 * <p><strong>PostgreSQL first, Redis second.</strong> The unique index is the correctness
 * boundary (ADR-0004) and the Redis claim is a contention optimiser, so the durable write must
 * be the one that decides. Releasing the claim first would briefly advertise a driver as
 * available while a committed row still holds them — harmless, because a competing claim would
 * then be rejected at the index, but it converts a cheap Redis rejection into an expensive
 * database one, which is precisely the trade the claim exists to avoid.
 *
 * <p>The cost of that ordering is a window: a crash after the commit and before the release
 * leaves a driver reserved in Redis with no active trip. That is not hypothetical —
 * {@code markOnTrip} calls {@code PERSIST} on the claim marker, so an in-progress driver has no
 * TTL left to rescue them. {@link ClaimReconciler} closes the window by converging Redis to
 * what PostgreSQL says, which is the only direction that can be correct.
 */
public class TripLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TripLifecycle.class);

    private final TripStore trips;
    private final DriverClaimStore claims;
    private final Clock clock;

    private final Counter accepted;
    private final Counter started;
    private final Counter completed;
    private final Counter cancelled;
    private final Counter released;
    private final Counter releaseFailed;

    public TripLifecycle(TripStore trips, DriverClaimStore claims, MeterRegistry meters, Clock clock) {
        this.trips = trips;
        this.claims = claims;
        this.clock = clock;

        this.accepted = counter(meters, "trip.accepted", "Offers accepted by a driver");
        this.started = counter(meters, "trip.started", "Trips where the rider was picked up");
        this.completed = counter(meters, "trip.completed", "Trips finished normally");
        this.cancelled = counter(meters, "trip.cancelled", "Trips called off before they began");
        // The pool-health metric. Releases should track completions plus cancellations; a
        // persistent shortfall means drivers are leaking out of the pool, which a load test
        // sees as a falling match rate long before anything errors.
        this.released = counter(meters, "driver.claim.released", "Claims handed back to the pool");
        this.releaseFailed = counter(meters, "driver.claim.release.failed",
                "Claims whose release did not take effect");
    }

    private static Counter counter(MeterRegistry meters, String name, String description) {
        return Counter.builder(name).description(description).register(meters);
    }

    /** The driver accepted the offer. No event: nothing downstream acts on an acceptance yet. */
    public TripStatus accept(RideId rideId) {
        return transition(rideId, Trip::accept, null, accepted);
    }

    /** The rider is in the vehicle. */
    public TripStatus start(RideId rideId) {
        return transition(rideId, Trip::startTrip, null, started);
    }

    /**
     * The rider was dropped off. Terminal, and the point at which the driver goes back into
     * the pool.
     */
    public TripStatus complete(RideId rideId) {
        return transition(rideId, Trip::complete,
                (trip, held) -> new RideCompleted(UUID.randomUUID(), trip.id().value(),
                        held.driverId() == null ? null : held.driverId().value(),
                        trip.directDistanceMeters(), clock.instant()),
                completed);
    }

    /**
     * The trip was called off. Terminal.
     *
     * <p>Illegal once {@code IN_PROGRESS} — the domain rejects it, and that rejection surfaces
     * as a 409 rather than being quietly coerced into something legal. See {@link TripStatus}.
     */
    public TripStatus cancel(RideId rideId, String reason) {
        return transition(rideId, trip -> trip.cancel(reason),
                (trip, held) -> new RideCancelled(UUID.randomUUID(), trip.id().value(),
                        held.driverId() == null ? null : held.driverId().value(),
                        reason, clock.instant()),
                cancelled);
    }

    /**
     * Loads a trip for reading.
     *
     * <p>Returns the aggregate rather than a row so the caller sees the same status the
     * transition guard would.
     */
    public Optional<Trip> find(RideId rideId) {
        return trips.load(rideId).map(TripStore.Versioned::trip);
    }

    private TripStatus transition(RideId rideId,
                                  Consumer<Trip> change,
                                  BiFunction<Trip, HeldClaim, DomainEvent> eventFactory,
                                  Counter metric) {
        TripStore.Versioned loaded = trips.load(rideId)
                .orElseThrow(() -> new TripNotFoundException(rideId));
        Trip trip = loaded.trip();

        // Captured BEFORE the transition. cancel() and markUnmatched() clear the assignment,
        // and the claim cannot be released without knowing whose it was — reading these
        // afterwards would find nothing and silently strand the driver.
        HeldClaim held = HeldClaim.of(trip);

        change.accept(trip);

        // Built after the transition so it reports the state that was actually reached, but
        // told the driver separately: cancel() and complete() clear the assignment, and an
        // event that named nobody would leave billing and notification unable to say whose
        // trip just ended. The driver is only genuinely absent when none was ever held.
        DomainEvent event = eventFactory == null ? null : eventFactory.apply(trip, held);

        trips.save(trip, loaded.version(), event);
        metric.increment();

        if (trip.status() == TripStatus.ACCEPTED) {
            pinClaim(rideId, held);
        } else if (trip.status().isTerminal()) {
            releaseClaim(rideId, held);
        }
        return trip.status();
    }

    /**
     * Stops the claim expiring underneath a driver who has committed to the trip.
     *
     * <p>The claim TTL is sized for an <em>offer</em> — seconds, long enough for a driver to
     * tap accept. A trip lasts twenty minutes. Without this the marker would expire while the
     * driver was still en route, Redis would advertise them as available, and the matching
     * engine would keep offering them rides that the {@code uniq_driver_active_trip} index
     * then rejects: correct, but a candidate slot burned on every attempt for the length of
     * the journey.
     *
     * <p>{@code markOnTrip} calls {@code PERSIST}, which is what makes the release at the end
     * mandatory rather than merely tidy — there is no TTL left to clean up after it.
     */
    private void pinClaim(RideId rideId, HeldClaim held) {
        if (!held.present()) {
            return;
        }
        try {
            claims.markOnTrip(held.driverId(), held.offerToken());
        } catch (RuntimeException e) {
            // Not fatal: the acceptance is committed. The worst case is the wasted-offer
            // churn described above, which the reconciler ends when the trip finishes.
            log.warn("Could not pin claim for driver {} on trip {}: {}",
                    held.driverId(), rideId, e.toString());
        }
    }

    /**
     * Hands the driver back after the terminal state is durable.
     *
     * <p>Failures are logged and counted, never rethrown. The trip is already committed and
     * the caller's request genuinely succeeded; turning a Redis hiccup into a 500 would tell a
     * rider their completed trip failed. The reconciler picks up whatever this misses.
     */
    private void releaseClaim(RideId rideId, HeldClaim held) {
        if (!held.present()) {
            // A trip cancelled while still MATCHING never held a driver. Not an error.
            return;
        }
        try {
            if (claims.release(held.driverId(), held.offerToken())) {
                released.increment();
            } else {
                // The token no longer owns the marker: the claim expired and someone else has
                // the driver, or a reconciler already released it. Either way the driver is
                // not ours to free, and deleting the marker anyway would take a driver away
                // from whoever legitimately holds them.
                releaseFailed.increment();
                log.debug("Claim for driver {} on trip {} was not ours to release",
                        held.driverId(), rideId);
            }
        } catch (RuntimeException e) {
            releaseFailed.increment();
            log.warn("Could not release claim for driver {} on trip {}; "
                    + "the reconciler will retry: {}", held.driverId(), rideId, e.toString());
        }
    }

    /** The driver and claim token a trip held at the moment it was read. */
    private record HeldClaim(DriverId driverId, String offerToken) {

        static HeldClaim of(Trip trip) {
            return new HeldClaim(trip.assignedDriver().orElse(null),
                    trip.offerToken().orElse(null));
        }

        boolean present() {
            return driverId != null && offerToken != null;
        }
    }
}
