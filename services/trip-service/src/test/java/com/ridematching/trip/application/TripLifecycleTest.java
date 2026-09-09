package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.IllegalTripTransitionException;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.events.RideCancelled;
import com.ridematching.events.RideCompleted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle, and specifically what happens to the driver at the end of it.
 *
 * <p>Before these transitions existed, a claimed driver was never handed back by any code path
 * — {@code DriverClaimStore.release} was implemented, wired, and called by nothing. The pool
 * drained monotonically and the only thing returning drivers to it was the claim TTL, which is
 * a crash-safety valve rather than a lifecycle. Half of what is asserted here is therefore not
 * "the status changed" but "the driver is available again", because the status was never the
 * part that was broken.
 */
class TripLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T10:15:00Z"), ZoneOffset.UTC);
    private static final String OFFER_TOKEN = "offer-abc";

    private FakeTripStore trips;
    private FakeClaimStore claims;
    private TripLifecycle lifecycle;

    private RideId rideId;
    private DriverId driverId;

    @BeforeEach
    void setUp() {
        trips = new FakeTripStore(CLOCK);
        claims = new FakeClaimStore();
        lifecycle = new TripLifecycle(trips, claims, new SimpleMeterRegistry(), CLOCK);

        driverId = DriverId.newId();
        rideId = new RideId(java.util.UUID.randomUUID());
    }

    /** A trip carried up to {@code target}, with the driver claimed as the matcher leaves them. */
    private void givenTripAt(TripStatus target) {
        Trip trip = Trip.request(rideId, RiderId.newId(),
                new Coordinates(30.0444, 31.2357), new Coordinates(30.0561, 31.2394),
                VehicleClass.STANDARD, CLOCK);

        if (target != TripStatus.REQUESTED) {
            trip.beginMatching();
        }
        if (target.holdsDriver()) {
            trip.offerTo(driverId, 42L, OFFER_TOKEN);
            claims.reserved(driverId, OFFER_TOKEN);
        }
        if (target == TripStatus.ACCEPTED || target == TripStatus.IN_PROGRESS) {
            trip.accept();
        }
        if (target == TripStatus.IN_PROGRESS) {
            trip.startTrip();
        }
        trips.put(trip);
    }

    @Nested
    @DisplayName("finishing a trip")
    class Completion {

        @Test
        @DisplayName("puts the driver back in the pool")
        void completionReleasesTheDriver() {
            givenTripAt(TripStatus.IN_PROGRESS);

            TripStatus status = lifecycle.complete(rideId);

            assertThat(status).isEqualTo(TripStatus.COMPLETED);
            // The assertion that matters. Without the release call in TripLifecycle this line
            // fails while every status assertion in this file still passes.
            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
            assertThat(claims.holdsClaim(driverId)).isFalse();
        }

        @Test
        @DisplayName("publishes a completion event through the outbox")
        void completionPublishesAnEvent() {
            givenTripAt(TripStatus.IN_PROGRESS);

            lifecycle.complete(rideId);

            assertThat(trips.published())
                    .singleElement()
                    .isInstanceOfSatisfying(RideCompleted.class, event -> {
                        assertThat(event.rideId()).isEqualTo(rideId.value());
                        assertThat(event.driverId()).isEqualTo(driverId.value());
                    });
        }

        @Test
        @DisplayName("cannot be completed twice")
        void completingTwiceIsRejected() {
            givenTripAt(TripStatus.IN_PROGRESS);
            lifecycle.complete(rideId);

            // COMPLETED is terminal. A duplicate request — a retried driver tap, a redelivered
            // message — must not re-release a driver who has since been claimed for a new ride.
            assertThatThrownBy(() -> lifecycle.complete(rideId))
                    .isInstanceOf(IllegalTripTransitionException.class);
        }
    }

    @Nested
    @DisplayName("cancelling")
    class Cancellation {

        @Test
        @DisplayName("releases a driver who had been offered the trip")
        void cancellingAnOfferedTripReleasesTheDriver() {
            givenTripAt(TripStatus.OFFERED);

            lifecycle.cancel(rideId, "rider changed their mind");

            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("names the driver on the event even though the trip has let them go")
        void cancellationEventKeepsTheDriver() {
            givenTripAt(TripStatus.OFFERED);

            lifecycle.cancel(rideId, "rider changed their mind");

            // Trip.cancel() clears the assignment, so this only holds because the driver is
            // read before the transition. Consumers need to know who was stood down.
            assertThat(trips.published())
                    .singleElement()
                    .isInstanceOfSatisfying(RideCancelled.class, event -> {
                        assertThat(event.driverId()).isEqualTo(driverId.value());
                        assertThat(event.reason()).isEqualTo("rider changed their mind");
                    });
        }

        @Test
        @DisplayName("is fine when no driver was ever held")
        void cancellingWhileMatchingReleasesNothing() {
            givenTripAt(TripStatus.MATCHING);

            TripStatus status = lifecycle.cancel(rideId, "rider changed their mind");

            assertThat(status).isEqualTo(TripStatus.CANCELLED);
            assertThat(trips.published())
                    .singleElement()
                    .isInstanceOfSatisfying(RideCancelled.class,
                            event -> assertThat(event.driverId()).isNull());
        }

        @Test
        @DisplayName("is refused once the rider is in the vehicle")
        void cancellingInProgressIsRefused() {
            givenTripAt(TripStatus.IN_PROGRESS);

            assertThatThrownBy(() -> lifecycle.cancel(rideId, "changed my mind"))
                    .isInstanceOf(IllegalTripTransitionException.class);

            // And the driver stays held. A rejected transition must not have side effects.
            assertThat(claims.holdsClaim(driverId)).isTrue();
        }
    }

    @Nested
    @DisplayName("accepting")
    class Acceptance {

        @Test
        @DisplayName("pins the claim so it cannot expire mid-journey")
        void acceptancePinsTheClaim() {
            givenTripAt(TripStatus.OFFERED);

            lifecycle.accept(rideId);

            // The claim TTL is sized for an offer, in seconds. A trip lasts far longer, and an
            // expired marker would let the matching engine keep offering rides to a driver who
            // is already committed — rejected at the index every time, but a candidate slot
            // burned on each attempt.
            assertThat(claims.isPinned(driverId)).isTrue();
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");
        }

        @Test
        @DisplayName("is refused when the trip is not under offer")
        void acceptingAMatchingTripIsRefused() {
            givenTripAt(TripStatus.MATCHING);

            assertThatThrownBy(() -> lifecycle.accept(rideId))
                    .isInstanceOf(IllegalTripTransitionException.class);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("a write loses if the trip moved since it was read")
        void staleWriteIsRejected() {
            givenTripAt(TripStatus.OFFERED);
            trips.bumpVersionAfterNextLoad(rideId);

            assertThatThrownBy(() -> lifecycle.accept(rideId))
                    .isInstanceOf(ConcurrentTripModificationException.class);
        }
    }

    @Nested
    @DisplayName("when Redis is unreachable")
    class RedisFailure {

        @Test
        @DisplayName("the trip still completes")
        void releaseFailureDoesNotFailTheRequest() {
            givenTripAt(TripStatus.IN_PROGRESS);
            claims.failWith(new IllegalStateException("connection refused"));

            // The completion is already committed and the rider's trip genuinely finished.
            // Failing the request here would report a successful trip as an error; the
            // reconciler is what recovers the claim.
            TripStatus status = lifecycle.complete(rideId);

            assertThat(status).isEqualTo(TripStatus.COMPLETED);
        }
    }

    @Test
    @DisplayName("a missing trip is not found rather than a server error")
    void unknownTripIsNotFound() {
        assertThatThrownBy(() -> lifecycle.accept(new RideId(java.util.UUID.randomUUID())))
                .isInstanceOf(TripNotFoundException.class);
    }
}
