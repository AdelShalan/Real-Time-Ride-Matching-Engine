package com.ridematching.location.application.port;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;

import java.time.Instant;
import java.util.List;

/**
 * The geospatial index of live driver positions — an outbound port (ADR-0001).
 *
 * <p>Redis is the implementation the system ships with (ADR-0002), but nothing above this
 * interface knows that. The interface is what makes the PostGIS comparison benchmark on the
 * roadmap possible: a second adapter, the same tests, the same load.
 */
public interface DriverLocationIndex {

    /**
     * Writes a batch of positions.
     *
     * <p>Batch rather than single-write because the target is 10,000 updates/sec. A round trip
     * per frame would spend the entire latency budget on network overhead; one pipelined call
     * per batch turns thousands of round trips per second into tens.
     */
    void upsertAll(List<DriverLocation> locations);

    /** Registers a driver as connected and dispatchable. */
    void markOnline(DriverId driverId);

    /**
     * Removes a driver from the index entirely.
     *
     * <p>Removal rather than a status flag: a disconnected driver must not be returned by a
     * proximity search at all, and filtering after the fact would waste candidate slots.
     */
    void markOffline(DriverId driverId);

    /** Candidates within {@code radiusMeters}, nearest first, capped at {@code limit}. */
    List<NearbyDriver> search(Coordinates center, double radiusMeters, int limit);

    /**
     * Drops drivers whose last report is older than {@code cutoff}, returning how many went.
     *
     * <p>Covers half-open connections: a socket that died without a close frame leaves a
     * "ghost driver" that would otherwise be matched and never respond.
     */
    int evictStaleOlderThan(Instant cutoff);

    /** Number of drivers currently in the index. Exposed as a gauge. */
    long size();
}
