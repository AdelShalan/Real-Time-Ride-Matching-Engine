package com.ridematching.domain.trip;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripTest {

    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);
    private static final Coordinates DROPOFF = new Coordinates(30.0500, 31.2400);
    private static final Instant T0 = Instant.parse("2026-09-04T09:00:00Z");

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    private Trip newTrip() {
        return Trip.request(RideId.newId(), RiderId.newId(), PICKUP, DROPOFF,
                VehicleClass.STANDARD, clock);
    }

    private Trip tripInMatching() {
        Trip trip = newTrip();
        trip.beginMatching();
        return trip;
    }

    private Trip tripWithOffer(DriverId driver) {
        Trip trip = tripInMatching();
        trip.offerTo(driver, 1L);
        return trip;
    }

    @Nested
    class Creation {

        @Test
        @DisplayName("a new trip starts in REQUESTED with no driver")
        void startsRequested() {
            Trip trip = newTrip();

            assertThat(trip.status()).isEqualTo(TripStatus.REQUESTED);
            assertThat(trip.assignedDriver()).isEmpty();
            assertThat(trip.fenceToken()).isEmpty();
            assertThat(trip.requestedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("creation is recorded in the history")
        void creationIsRecorded() {
            Trip trip = newTrip();

            assertThat(trip.history()).hasSize(1);
            TripTransition first = trip.history().get(0);
            assertThat(first.previousState()).isEmpty();
            assertThat(first.to()).isEqualTo(TripStatus.REQUESTED);
        }

        @Test
        @DisplayName("required fields are rejected when null")
        void rejectsNulls() {
            assertThatThrownBy(() -> Trip.request(null, RiderId.newId(), PICKUP, DROPOFF,
                    VehicleClass.STANDARD, clock))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> Trip.request(RideId.newId(), RiderId.newId(), PICKUP, null,
                    VehicleClass.STANDARD, clock))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class HappyPath {

        @Test
        @DisplayName("request -> matching -> offered -> accepted -> in progress -> completed")
        void fullLifecycle() {
            DriverId driver = DriverId.newId();
            Trip trip = newTrip();

            trip.beginMatching();
            assertThat(trip.status()).isEqualTo(TripStatus.MATCHING);

            trip.offerTo(driver, 8817L);
            assertThat(trip.status()).isEqualTo(TripStatus.OFFERED);
            assertThat(trip.assignedDriver()).contains(driver);
            assertThat(trip.fenceToken()).contains(8817L);

            trip.accept();
            assertThat(trip.status()).isEqualTo(TripStatus.ACCEPTED);
            assertThat(trip.matchedAt()).contains(T0);

            trip.startTrip();
            assertThat(trip.status()).isEqualTo(TripStatus.IN_PROGRESS);

            trip.complete();
            assertThat(trip.status()).isEqualTo(TripStatus.COMPLETED);
            assertThat(trip.completedAt()).contains(T0);
            assertThat(trip.status().isTerminal()).isTrue();
        }

        @Test
        @DisplayName("every step is appended to the audit trail in order")
        void historyRecordsEveryStep() {
            Trip trip = newTrip();
            trip.beginMatching();
            trip.offerTo(DriverId.newId(), 1L);
            trip.accept();
            trip.startTrip();
            trip.complete();

            assertThat(trip.history())
                    .extracting(TripTransition::to)
                    .containsExactly(
                            TripStatus.REQUESTED,
                            TripStatus.MATCHING,
                            TripStatus.OFFERED,
                            TripStatus.ACCEPTED,
                            TripStatus.IN_PROGRESS,
                            TripStatus.COMPLETED);
        }
    }

    @Nested
    class OfferHandling {

        @Test
        @DisplayName("a declined offer releases the driver and resumes matching")
        void declinedOfferReleasesDriver() {
            DriverId driver = DriverId.newId();
            Trip trip = tripWithOffer(driver);

            trip.releaseOffer("driver declined");

            assertThat(trip.status()).isEqualTo(TripStatus.MATCHING);
            assertThat(trip.assignedDriver())
                    .as("a released driver must be free to take another ride")
                    .isEmpty();
        }

        @Test
        @DisplayName("a second offer after a release requires a higher fence token")
        void reofferNeedsAdvancingFenceToken() {
            Trip trip = tripWithOffer(DriverId.newId());
            trip.releaseOffer("offer expired");

            // A stale worker waking up late and replaying its old claim must be rejected:
            // this is the failure mode plain lease locks allow (ADR-0004).
            assertThatThrownBy(() -> trip.offerTo(DriverId.newId(), 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Stale fence token");

            trip.offerTo(DriverId.newId(), 2L);
            assertThat(trip.status()).isEqualTo(TripStatus.OFFERED);
        }

        @Test
        @DisplayName("fence tokens must be positive")
        void rejectsNonPositiveFenceToken() {
            Trip trip = tripInMatching();

            assertThatThrownBy(() -> trip.offerTo(DriverId.newId(), 0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the release is recorded with the driver that was freed")
        void releaseIsAudited() {
            DriverId driver = DriverId.newId();
            Trip trip = tripWithOffer(driver);

            trip.releaseOffer("offer expired");

            assertThat(trip.history())
                    .anySatisfy(t -> assertThat(t.reason()).contains("released driver"));
        }
    }

    @Nested
    class IllegalTransitions {

        @Test
        @DisplayName("cannot accept an offer that was never made")
        void cannotAcceptWithoutOffer() {
            Trip trip = newTrip();

            assertThatExceptionOfType(IllegalTripTransitionException.class)
                    .isThrownBy(trip::accept)
                    .satisfies(e -> {
                        assertThat(e.from()).isEqualTo(TripStatus.REQUESTED);
                        assertThat(e.to()).isEqualTo(TripStatus.ACCEPTED);
                    });
        }

        @Test
        @DisplayName("cannot complete a trip that never started")
        void cannotCompleteBeforeStarting() {
            Trip trip = tripInMatching();

            assertThatExceptionOfType(IllegalTripTransitionException.class)
                    .isThrownBy(trip::complete);
        }

        @Test
        @DisplayName("cannot cancel a trip already in progress")
        void cannotCancelInProgress() {
            Trip trip = tripWithOffer(DriverId.newId());
            trip.accept();
            trip.startTrip();

            assertThatExceptionOfType(IllegalTripTransitionException.class)
                    .isThrownBy(() -> trip.cancel("rider changed their mind"));

            assertThat(trip.status()).isEqualTo(TripStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("a completed trip rejects every further transition")
        void completedIsFinal() {
            Trip trip = tripWithOffer(DriverId.newId());
            trip.accept();
            trip.startTrip();
            trip.complete();

            assertThatExceptionOfType(IllegalTripTransitionException.class)
                    .isThrownBy(() -> trip.cancel("too late"));
            assertThatExceptionOfType(IllegalTripTransitionException.class)
                    .isThrownBy(trip::beginMatching);
        }

        @Test
        @DisplayName("a rejected transition leaves the trip completely unchanged")
        void rejectedTransitionIsAtomic() {
            DriverId driver = DriverId.newId();
            Trip trip = tripWithOffer(driver);
            int historyBefore = trip.history().size();

            assertThatExceptionOfType(IllegalTripTransitionException.class)
                    .isThrownBy(trip::complete);

            // A failed transition that half-applied would be worse than one that throws:
            // the trip must be exactly as it was.
            assertThat(trip.status()).isEqualTo(TripStatus.OFFERED);
            assertThat(trip.assignedDriver()).contains(driver);
            assertThat(trip.history()).hasSize(historyBefore);
        }

        @Test
        @DisplayName("the exception names the allowed targets")
        void exceptionIsDiagnosable() {
            Trip trip = newTrip();

            assertThatThrownBy(trip::complete)
                    .hasMessageContaining("REQUESTED")
                    .hasMessageContaining("COMPLETED")
                    .hasMessageContaining("MATCHING");
        }
    }

    @Nested
    class Cancellation {

        @Test
        @DisplayName("a rider can cancel before matching starts")
        void cancelFromRequested() {
            Trip trip = newTrip();

            trip.cancel("rider cancelled");

            assertThat(trip.status()).isEqualTo(TripStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancelling after a driver accepted frees that driver")
        void cancelAfterAcceptFreesDriver() {
            Trip trip = tripWithOffer(DriverId.newId());
            trip.accept();

            trip.cancel("rider cancelled late");

            assertThat(trip.status()).isEqualTo(TripStatus.CANCELLED);
            assertThat(trip.assignedDriver()).isEmpty();
        }

        @Test
        @DisplayName("no driver found within the SLA window")
        void unmatched() {
            Trip trip = tripInMatching();

            trip.markUnmatched("no driver within 5km after 30s");

            assertThat(trip.status()).isEqualTo(TripStatus.UNMATCHED);
            assertThat(trip.status().isTerminal()).isTrue();
        }
    }

    @Nested
    class Identity {

        @Test
        @DisplayName("trips are equal by id, not by state")
        void equalityIsByIdentity() {
            RideId sharedId = RideId.newId();
            Trip a = Trip.request(sharedId, RiderId.newId(), PICKUP, DROPOFF,
                    VehicleClass.STANDARD, clock);
            Trip b = Trip.request(sharedId, RiderId.newId(), PICKUP, DROPOFF,
                    VehicleClass.PREMIUM, clock);
            b.beginMatching();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("different ids are different trips")
        void differentIdsAreNotEqual() {
            assertThat(newTrip()).isNotEqualTo(newTrip());
        }
    }

    @Nested
    class Timing {

        @Test
        @DisplayName("timestamps come from the injected clock, so tests are deterministic")
        void usesInjectedClock() {
            Instant later = T0.plus(Duration.ofMinutes(12));
            Trip trip = Trip.request(RideId.newId(), RiderId.newId(), PICKUP, DROPOFF,
                    VehicleClass.STANDARD, Clock.fixed(later, ZoneOffset.UTC));

            assertThat(trip.requestedAt()).isEqualTo(later);
        }
    }

    @Nested
    class Encapsulation {

        @Test
        @DisplayName("history cannot be mutated from outside")
        void historyIsUnmodifiable() {
            Trip trip = newTrip();

            assertThatThrownBy(() -> trip.history().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
