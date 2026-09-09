package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Converges Redis back to what PostgreSQL says about who is driving.
 *
 * <p>Releasing a claim happens after the trip's terminal state commits, which leaves a window:
 * a crash in between hands back nothing, and because an accepted trip's claim marker has had
 * its TTL removed by {@code markOnTrip}, nothing expires to clean it up either. Left alone,
 * every such crash retires a driver permanently. Over a long load test that is a pool which
 * quietly drains, showing up as a falling match rate with no error anywhere to explain it.
 *
 * <p>The direction of the sync is the design. PostgreSQL is the system of record (ADR-0004),
 * so this reads the claims Redis is holding and asks the database whether each is justified —
 * never the reverse. A reconciler that trusted Redis could delete a legitimate assignment.
 *
 * <p><strong>Only claims with no TTL are candidates.</strong> That single condition removes
 * the race this kind of sweep usually has. A claim still carrying a TTL is either an offer in
 * flight — possibly one whose {@code RideMatched} event has not been consumed yet, so no trip
 * row exists to justify it — or an abandoned one that will expire on its own; touching either
 * is at best pointless and at worst steals a driver from a match that was about to land. A
 * claim with no TTL can only have been created by {@code markOnTrip}, which runs after an
 * acceptance commits. If that claim now has no trip holding it, the trip is finished and the
 * release was missed. Nothing else produces that combination.
 */
public class ClaimReconciler {

    private static final Logger log = LoggerFactory.getLogger(ClaimReconciler.class);

    /** Redis reports this from {@code TTL} for a key that exists but has no expiry. */
    private static final long NO_EXPIRY = -1L;

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final DriverClaimStore claims;
    private final RedisKeys keys;
    private final int scanBatch;

    private final Counter reclaimed;

    public ClaimReconciler(StringRedisTemplate redis,
                           JdbcClient jdbc,
                           DriverClaimStore claims,
                           RedisKeys keys,
                           MeterRegistry meters,
                           int scanBatch) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.claims = claims;
        this.keys = keys;
        this.scanBatch = scanBatch;

        // Should sit at zero in a healthy run. A rising count means claims are leaking
        // upstream and this is papering over it rather than the release path working — worth
        // an alert, not just a graph.
        this.reclaimed = Counter.builder("driver.claim.reclaimed")
                .description("Stranded claims released by the reconciler").register(meters);
    }

    /**
     * One pass. Returns how many claims were reclaimed.
     *
     * <p>{@code SCAN} rather than {@code KEYS}: this runs against the same Redis that serves
     * every driver location update, and {@code KEYS} blocks the single command thread for the
     * length of the keyspace. A cursor scan may miss a key that appears mid-pass, which is
     * fine — the next pass sees it.
     */
    public int reconcile() {
        List<Candidate> candidates = new ArrayList<>();

        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(keys.claimPattern())
                .count(scanBatch)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
                if (ttl == null || ttl != NO_EXPIRY) {
                    continue;
                }
                driverIdOf(key).ifPresent(driverId -> candidates.add(new Candidate(driverId, key)));
            }
        }

        if (candidates.isEmpty()) {
            return 0;
        }

        int released = 0;
        for (Candidate candidate : withoutActiveTrip(candidates)) {
            // Re-read the token now rather than during the scan: it must be the value the
            // release script will compare against, and the claim may have turned over since.
            String token = redis.opsForValue().get(candidate.key());
            if (token == null) {
                continue;
            }
            if (claims.release(new DriverId(candidate.driverId()), token)) {
                released++;
                reclaimed.increment();
                log.info("Reclaimed stranded claim for driver {}", candidate.driverId());
            }
        }
        return released;
    }

    /**
     * Filters to the drivers PostgreSQL says are not on a trip.
     *
     * <p>One query for the whole batch rather than one per driver: a sweep that issues a round
     * trip per key becomes a load source of its own.
     */
    private List<Candidate> withoutActiveTrip(List<Candidate> candidates) {
        List<UUID> ids = candidates.stream().map(Candidate::driverId).toList();

        Set<UUID> active = Set.copyOf(jdbc.sql("""
                        SELECT driver_id FROM trips
                        WHERE driver_id IN (:ids)
                          AND status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS')
                        """)
                .param("ids", ids)
                .query(UUID.class)
                .list());

        return candidates.stream()
                .filter(candidate -> !active.contains(candidate.driverId()))
                .toList();
    }

    /** Pulls the driver id out of a {@code claim:{city}:<uuid>} key. */
    private static Optional<UUID> driverIdOf(String key) {
        int lastColon = key.lastIndexOf(':');
        if (lastColon < 0 || lastColon == key.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(key.substring(lastColon + 1)));
        } catch (IllegalArgumentException e) {
            // Not a driver claim key. Skipped rather than failing the pass: an unrelated key
            // matching the pattern is a reason to ignore it, not to stop reconciling.
            return Optional.empty();
        }
    }

    private record Candidate(UUID driverId, String key) {
    }
}
