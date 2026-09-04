package com.ridematching.location.application;

import com.ridematching.geoindex.DriverLocationIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * Evicts drivers whose connection died without a close frame.
 *
 * <p>A half-open socket leaves a "ghost driver": still in the geospatial index, still returned
 * by proximity searches, and guaranteed never to answer an offer. Every ghost wastes a
 * candidate slot and an offer TTL, so matching latency degrades quietly as they accumulate.
 *
 * <p>TCP alone will not surface this — a socket with no traffic can stay open indefinitely
 * from the server's point of view. The last-seen sorted set is the ground truth instead.
 */
public class StaleDriverReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleDriverReaper.class);

    private final DriverLocationIndex index;
    private final LocationIngestProperties properties;
    private final Clock clock;

    public StaleDriverReaper(DriverLocationIndex index,
                             LocationIngestProperties properties,
                             Clock clock) {
        this.index = index;
        this.properties = properties;
        this.clock = clock;
    }

    /** Sweeps once. Returns how many drivers were evicted. */
    public int reap() {
        Instant cutoff = clock.instant().minus(properties.getStaleAfter());
        try {
            int evicted = index.evictStaleOlderThan(cutoff);
            if (evicted > 0) {
                log.info("Evicted {} stale drivers last seen before {}", evicted, cutoff);
            }
            return evicted;
        } catch (RuntimeException e) {
            // A scheduled task that throws is silently unscheduled by some executors, which
            // would disable ghost eviction for the lifetime of the process. Swallow and
            // report; the next sweep tries again.
            log.warn("Stale driver sweep failed: {}", e.getMessage());
            return 0;
        }
    }
}
