package com.ridematching.domain.driver;

import com.ridematching.domain.geo.Coordinates;

import java.util.Objects;

/**
 * A candidate returned by a proximity search, with the distance the index computed.
 *
 * <p>The distance comes from Redis {@code GEOSEARCH} rather than being recomputed here: it is
 * the value the index actually ranked by, so carrying it forward keeps the matching engine's
 * ordering consistent with the query that produced it.
 *
 * @param driverId       the candidate
 * @param coordinates    their last reported position
 * @param distanceMeters straight-line distance from the search origin
 */
public record NearbyDriver(DriverId driverId, Coordinates coordinates, double distanceMeters) {

    public NearbyDriver {
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(coordinates, "coordinates");
        if (distanceMeters < 0.0) {
            throw new IllegalArgumentException("Distance must not be negative: " + distanceMeters);
        }
    }
}
