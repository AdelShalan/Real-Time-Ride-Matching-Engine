package com.ridematching.matching.application;

import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.DriverLocationIndex;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Turns a ride request into a claimed driver (ADR-0004).
 *
 * <p>Search an expanding radius, rank the candidates, and claim them in order until one
 * sticks. The loop is the important part: losing a claim is <strong>expected</strong> under
 * load, and the correct response is to move to the next candidate, never to retry the same
 * driver. Retrying a contended driver is how a hot pickup point turns into a queue.
 */
public class DriverMatcher {

    private final DriverLocationIndex index;
    private final DriverClaimStore claims;
    private final MatchingProperties properties;

    private final Counter matched;
    private final Counter unmatched;
    private final Counter contention;
    private final Timer duration;

    public DriverMatcher(DriverLocationIndex index,
                         DriverClaimStore claims,
                         MatchingProperties properties,
                         MeterRegistry meters) {
        this.index = index;
        this.claims = claims;
        this.properties = properties;

        this.matched = Counter.builder("matching.matched")
                .description("Requests that ended with a claimed driver").register(meters);
        this.unmatched = Counter.builder("matching.unmatched")
                .description("Requests that exhausted the radius without a driver").register(meters);
        // The metric that makes contention observable rather than a mystery. A load test
        // where this stays zero has not actually exercised the race (LOAD_TESTING.md, S3).
        this.contention = Counter.builder("driver.claim.contention")
                .description("Claims lost to another worker").register(meters);
        this.duration = Timer.builder("matching.duration")
                .description("Time from request to claimed driver").register(meters);
    }

    /**
     * Finds and claims the best available driver for {@code pickup}.
     *
     * @param offerToken opaque token identifying this offer; used to release or confirm later
     * @return the claimed driver and its fence token, or empty if none could be claimed
     */
    public Optional<MatchedDriver> match(Coordinates pickup, String offerToken) {
        return duration.record(() -> attemptMatch(pickup, offerToken));
    }

    private Optional<MatchedDriver> attemptMatch(Coordinates pickup, String offerToken) {
        for (double radiusMeters : properties.getSearchRadiiMeters()) {
            List<NearbyDriver> candidates =
                    index.search(pickup, radiusMeters, properties.getCandidateLimit());

            for (NearbyDriver candidate : rank(candidates)) {
                ClaimResult claim = claims.claim(
                        candidate.driverId(), offerToken, properties.getClaimTtl());

                if (claim.won()) {
                    matched.increment();
                    return Optional.of(new MatchedDriver(
                            candidate.driverId(),
                            claim.fenceToken(),
                            candidate.distanceMeters(),
                            offerToken));
                }

                // Someone else got there first. Counted, not logged as an error: under load
                // this is the system working, not failing.
                if (ClaimResult.ALREADY_CLAIMED.equals(claim.reason())
                        || ClaimResult.NOT_AVAILABLE.equals(claim.reason())) {
                    contention.increment();
                }
            }
            // Nothing claimable at this radius — widen rather than give up. A wider search is
            // cheaper than telling a rider there are no drivers.
        }

        unmatched.increment();
        return Optional.empty();
    }

    /**
     * Orders candidates.
     *
     * <p>Currently nearest-first, which is what {@code GEOSEARCH} already returns, so this is
     * a no-op pass-through that exists to hold the seam. The composite score from
     * ARCHITECTURE.md — ETA, acceptance rate, heading, idle time — needs driver history the
     * system does not yet collect, and inventing a score over data that does not exist would
     * be worse than admitting the ranking is simple today.
     */
    private List<NearbyDriver> rank(List<NearbyDriver> candidates) {
        return candidates;
    }

    /**
     * A successfully claimed driver.
     *
     * @param driverId       who was claimed
     * @param fenceToken     monotonic token that must accompany the durable assignment write
     * @param distanceMeters straight-line distance from the pickup point
     * @param offerToken     token required to release or confirm this claim
     */
    public record MatchedDriver(com.ridematching.domain.driver.DriverId driverId,
                                long fenceToken,
                                double distanceMeters,
                                String offerToken) {
    }
}
