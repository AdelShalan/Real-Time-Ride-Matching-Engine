package com.ridematching.domain.geo;

/**
 * A WGS-84 position. Validated on construction, so an invalid coordinate cannot exist
 * anywhere in the system — there is no "unvalidated coordinate" state to defend against
 * in the matching engine or the location ingest path.
 *
 * @param latitude  degrees north, -90..90
 * @param longitude degrees east, -180..180
 */
public record Coordinates(double latitude, double longitude) {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    public Coordinates {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IllegalArgumentException("Coordinates must not be NaN");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
    }

    /**
     * Great-circle distance in metres.
     *
     * <p>This is a straight-line approximation over a spherical earth, not a road-network
     * distance. That is a deliberate simplification (see the non-goals in ARCHITECTURE.md):
     * it is the same metric Redis {@code GEOSEARCH} ranks by, so candidate ordering stays
     * consistent between the index query and any re-ranking done here.
     */
    public double distanceMetersTo(Coordinates other) {
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = Math.toRadians(other.latitude - this.latitude);
        double deltaLon = Math.toRadians(other.longitude - this.longitude);

        double sinHalfLat = Math.sin(deltaLat / 2);
        double sinHalfLon = Math.sin(deltaLon / 2);

        double a = sinHalfLat * sinHalfLat
                + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;

        // atan2 rather than asin: numerically stable for antipodal points.
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
