package com.ridematching.matching.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.DriverLocationIndex;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The matching loop with no Redis: the ports make the interesting behaviour — what happens
 * when claims are LOST — testable directly, which is otherwise awkward to provoke on purpose.
 */
class DriverMatcherTest {

    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);

    /** Returns configured candidates per radius, so radius expansion can be observed. */
    private static final class StubIndex implements DriverLocationIndex {
        final Map<Double, List<NearbyDriver>> byRadius = new HashMap<>();
        final List<Double> searchedRadii = new ArrayList<>();

        @Override
        public List<NearbyDriver> search(Coordinates center, double radiusMeters, int limit) {
            searchedRadii.add(radiusMeters);
            return byRadius.getOrDefault(radiusMeters, List.of());
        }

        @Override
        public void upsertAll(List<DriverLocation> locations) { }

        @Override
        public void markOnline(DriverId driverId) { }

        @Override
        public void markOffline(DriverId driverId) { }

        @Override
        public int evictStaleOlderThan(Instant cutoff) {
            return 0;
        }

        @Override
        public long size() {
            return 0;
        }
    }

    /** Grants claims only for drivers in {@code claimable}, recording every attempt. */
    private static final class StubClaims implements DriverClaimStore {
        final Set<DriverId> claimable = new java.util.HashSet<>();
        final List<DriverId> attempts = new ArrayList<>();
        long nextFence = 100;

        @Override
        public ClaimResult claim(DriverId driverId, String offerToken, Duration ttl) {
            attempts.add(driverId);
            return claimable.contains(driverId)
                    ? ClaimResult.won(nextFence++)
                    : ClaimResult.lost(ClaimResult.ALREADY_CLAIMED);
        }

        @Override
        public boolean release(DriverId driverId, String offerToken) {
            return true;
        }

        @Override
        public void markOnTrip(DriverId driverId, String offerToken) { }

        @Override
        public String statusOf(DriverId driverId) {
            return "AVAILABLE";
        }

        @Override
        public Optional<Long> fenceOf(DriverId driverId) {
            return Optional.empty();
        }
    }

    private StubIndex index;
    private StubClaims claims;
    private MatchingProperties properties;
    private MeterRegistry meters;
    private DriverMatcher matcher;

    @BeforeEach
    void setUp() {
        index = new StubIndex();
        claims = new StubClaims();
        properties = new MatchingProperties();
        meters = new SimpleMeterRegistry();
        matcher = new DriverMatcher(index, claims, properties, meters);
    }

    private NearbyDriver candidate(double distanceMeters) {
        return new NearbyDriver(DriverId.newId(), PICKUP, distanceMeters);
    }

    private double counter(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Nested
    class HappyPath {

        @Test
        @DisplayName("claims the nearest candidate and returns its fence token")
        void claimsNearest() {
            NearbyDriver nearest = candidate(120);
            index.byRadius.put(2_000.0, List.of(nearest, candidate(400)));
            claims.claimable.add(nearest.driverId());

            var match = matcher.match(PICKUP, "offer-1");

            assertThat(match).isPresent();
            assertThat(match.get().driverId()).isEqualTo(nearest.driverId());
            assertThat(match.get().fenceToken()).isEqualTo(100);
            assertThat(counter("matching.matched")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("stops at the first radius that yields a claim")
        void stopsAtFirstSuccess() {
            NearbyDriver near = candidate(100);
            index.byRadius.put(2_000.0, List.of(near));
            claims.claimable.add(near.driverId());

            matcher.match(PICKUP, "offer-1");

            assertThat(index.searchedRadii)
                    .as("a wider search is wasted work once a driver is claimed")
                    .containsExactly(2_000.0);
        }
    }

    @Nested
    class Contention {

        @Test
        @DisplayName("a lost claim falls through to the next candidate, not a retry")
        void fallsThroughOnLostClaim() {
            NearbyDriver taken = candidate(100);
            NearbyDriver free = candidate(250);
            index.byRadius.put(2_000.0, List.of(taken, free));
            claims.claimable.add(free.driverId());

            var match = matcher.match(PICKUP, "offer-1");

            assertThat(match).isPresent();
            assertThat(match.get().driverId()).isEqualTo(free.driverId());
            assertThat(claims.attempts)
                    .as("each driver must be tried once; retrying a contended driver queues")
                    .containsExactly(taken.driverId(), free.driverId());
        }

        @Test
        @DisplayName("lost claims are counted so contention is observable")
        void lostClaimsAreCounted() {
            NearbyDriver taken = candidate(100);
            NearbyDriver free = candidate(250);
            index.byRadius.put(2_000.0, List.of(taken, free));
            claims.claimable.add(free.driverId());

            matcher.match(PICKUP, "offer-1");

            // A load test where this stays zero has not exercised the race at all.
            assertThat(counter("driver.claim.contention")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a contended driver is never claimed twice in one request")
        void neverRetriesSameDriver() {
            NearbyDriver taken = candidate(100);
            index.byRadius.put(2_000.0, List.of(taken));
            index.byRadius.put(3_200.0, List.of());
            index.byRadius.put(5_000.0, List.of());

            matcher.match(PICKUP, "offer-1");

            assertThat(claims.attempts).containsExactly(taken.driverId());
        }
    }

    @Nested
    class RadiusExpansion {

        @Test
        @DisplayName("widens the search when the near radius is empty")
        void widensWhenEmpty() {
            NearbyDriver far = candidate(4_500);
            index.byRadius.put(5_000.0, List.of(far));
            claims.claimable.add(far.driverId());

            var match = matcher.match(PICKUP, "offer-1");

            assertThat(match).isPresent();
            assertThat(index.searchedRadii).containsExactly(2_000.0, 3_200.0, 5_000.0);
        }

        @Test
        @DisplayName("widens when every near candidate is already claimed")
        void widensWhenAllContended() {
            NearbyDriver taken = candidate(100);
            NearbyDriver far = candidate(4_000);
            index.byRadius.put(2_000.0, List.of(taken));
            index.byRadius.put(5_000.0, List.of(far));
            claims.claimable.add(far.driverId());

            var match = matcher.match(PICKUP, "offer-1");

            assertThat(match.orElseThrow().driverId()).isEqualTo(far.driverId());
        }

        @Test
        @DisplayName("reports unmatched once the radii are exhausted")
        void unmatchedWhenNothingClaimable() {
            var match = matcher.match(PICKUP, "offer-1");

            assertThat(match).isEmpty();
            assertThat(counter("matching.unmatched")).isEqualTo(1.0);
            assertThat(index.searchedRadii).hasSize(3);
        }
    }

    @Nested
    class Configuration {

        @Test
        @DisplayName("the configured radii are used, in order")
        void radiiAreConfigurable() {
            properties.setSearchRadiiMeters(List.of(500.0, 1_500.0));

            matcher.match(PICKUP, "offer-1");

            assertThat(index.searchedRadii).containsExactly(500.0, 1_500.0);
        }

        @Test
        @DisplayName("the claim TTL outlives the offer TTL")
        void claimOutlivesOffer() {
            // If the offer could outlive the claim, a driver could be re-claimed in the gap
            // between the offer lapsing and the engine noticing.
            assertThat(properties.getClaimTtl())
                    .isGreaterThan(properties.getOfferTtl());
        }
    }
}
