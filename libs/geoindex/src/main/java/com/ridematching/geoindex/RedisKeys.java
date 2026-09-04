package com.ridematching.geoindex;

import com.ridematching.domain.driver.DriverId;

/**
 * The single owner of the Redis key layout.
 *
 * <p>Two services read and write these keys: location-service maintains the index, and the
 * matching engine searches it and claims drivers in it. If each built its own key strings, a
 * one-character divergence would produce two disjoint namespaces — every search returning
 * empty, with nothing failing loudly. Centralising the layout makes that impossible.
 *
 * <p><strong>Hash tags.</strong> Every key embeds {@code {city}} in braces. Redis Cluster
 * hashes only the braced portion, so all keys for a city land on one slot. That is not a
 * nicety: {@code GEOSEARCH} cannot span slots, and neither can the Lua claim script, which
 * touches three keys at once (ADR-0004).
 */
public final class RedisKeys {

    private final String city;

    public RedisKeys(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city must not be blank");
        }
        this.city = city;
    }

    /** Geospatial sorted set of live driver positions. */
    public String geoIndex() {
        return "geo:drivers:{%s}".formatted(city);
    }

    /** Sorted set scored by last-report epoch millis; input to the stale-driver reaper. */
    public String lastSeen() {
        return "driver:seen:{%s}".formatted(city);
    }

    /** Per-driver state hash: status, and the fence token of the current claim. */
    public String driverState(DriverId driverId) {
        return "driver:{%s}:%s:state".formatted(city, driverId);
    }

    /** Same as {@link #driverState(DriverId)} but for a raw id read back out of the index. */
    public String driverState(String driverId) {
        return "driver:{%s}:%s:state".formatted(city, driverId);
    }

    /** Short-lived claim marker holding the winning offer token. */
    public String claim(DriverId driverId) {
        return "claim:{%s}:%s".formatted(city, driverId);
    }

    /**
     * Monotonic source of fencing tokens.
     *
     * <p>One counter per city, incremented by the Lua script. Monotonicity is what lets a
     * resource reject a write from a worker whose lease expired while it was paused — the
     * failure mode plain lease locks permit (ADR-0004).
     */
    public String fenceCounter() {
        return "fence:{%s}:counter".formatted(city);
    }

    public String city() {
        return city;
    }
}
