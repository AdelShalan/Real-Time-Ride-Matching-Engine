package com.ridematching.domain.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CoordinatesTest {

    @Test
    @DisplayName("accepts a valid position")
    void acceptsValidPosition() {
        Coordinates cairo = new Coordinates(30.0444, 31.2357);

        assertThat(cairo.latitude()).isEqualTo(30.0444);
        assertThat(cairo.longitude()).isEqualTo(31.2357);
    }

    @ParameterizedTest(name = "rejects lat={0}, lng={1}")
    @CsvSource({
            "90.1,    0.0",
            "-90.1,   0.0",
            "0.0,     180.1",
            "0.0,    -180.1"
    })
    void rejectsOutOfRange(double lat, double lng) {
        assertThatThrownBy(() -> new Coordinates(lat, lng))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("accepts the exact boundary values")
    void acceptsBoundaries() {
        assertThat(new Coordinates(90.0, 180.0)).isNotNull();
        assertThat(new Coordinates(-90.0, -180.0)).isNotNull();
    }

    @Test
    void rejectsNaN() {
        assertThatThrownBy(() -> new Coordinates(Double.NaN, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("distance to self is zero")
    void distanceToSelfIsZero() {
        Coordinates c = new Coordinates(30.0444, 31.2357);

        assertThat(c.distanceMetersTo(c)).isZero();
    }

    @Test
    @DisplayName("distance is symmetric")
    void distanceIsSymmetric() {
        Coordinates a = new Coordinates(30.0444, 31.2357);
        Coordinates b = new Coordinates(30.0500, 31.2400);

        assertThat(a.distanceMetersTo(b)).isCloseTo(b.distanceMetersTo(a), within(1e-9));
    }

    @Test
    @DisplayName("matches the distance Redis GEODIST reports for the same pair")
    void matchesKnownDistance() {
        // Same two points used to smoke-test the Redis geo index during setup;
        // GEOSEARCH reported 0.7480 km between them.
        Coordinates driver1 = new Coordinates(30.0444, 31.2357);
        Coordinates driver2 = new Coordinates(30.0500, 31.2400);

        // 5 m tolerance: Redis models the earth as a sphere too, but with a slightly
        // different radius constant, so exact agreement is not expected.
        assertThat(driver1.distanceMetersTo(driver2)).isCloseTo(748.0, within(5.0));
    }

    @Test
    @DisplayName("handles antipodal points without NaN")
    void handlesAntipodalPoints() {
        Coordinates north = new Coordinates(90.0, 0.0);
        Coordinates south = new Coordinates(-90.0, 0.0);

        double halfCircumference = Math.PI * 6_371_008.8;

        assertThat(north.distanceMetersTo(south)).isCloseTo(halfCircumference, within(1.0));
    }
}
