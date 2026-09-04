package com.ridematching.domain.driver;

import com.ridematching.domain.geo.Coordinates;

import java.time.Instant;
import java.util.Objects;

/**
 * One GPS observation from a driver.
 *
 * <p>Validated on construction, so an out-of-range heading or a negative speed is rejected at
 * the edge rather than poisoning the geospatial index. Roughly 10,000 of these arrive per
 * second at target load, which is why the type is a record with no allocation beyond its
 * fields.
 *
 * @param driverId       who reported it
 * @param coordinates    where they are
 * @param headingDegrees compass bearing, 0..360; 0 is north
 * @param speedKph       ground speed, never negative
 * @param recordedAt     when the device took the reading, not when the server received it
 */
public record DriverLocation(
        DriverId driverId,
        Coordinates coordinates,
        double headingDegrees,
        double speedKph,
        Instant recordedAt) {

    public DriverLocation {
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(recordedAt, "recordedAt");

        if (Double.isNaN(headingDegrees) || headingDegrees < 0.0 || headingDegrees > 360.0) {
            throw new IllegalArgumentException("Heading out of range: " + headingDegrees);
        }
        if (Double.isNaN(speedKph) || speedKph < 0.0) {
            throw new IllegalArgumentException("Speed must not be negative: " + speedKph);
        }
    }

    /** Metres between this observation and {@code other}. */
    public double distanceMetersTo(DriverLocation other) {
        return coordinates.distanceMetersTo(other.coordinates());
    }

    /**
     * Whether the reading is too far from {@code now} to be trusted, in either direction.
     *
     * <p>Future-dated frames matter as much as stale ones: a device with a skewed clock would
     * otherwise keep winning "most recent position" forever.
     */
    public boolean isSkewedBeyond(Instant now, java.time.Duration tolerance) {
        java.time.Duration delta = java.time.Duration.between(recordedAt, now).abs();
        return delta.compareTo(tolerance) > 0;
    }
}
