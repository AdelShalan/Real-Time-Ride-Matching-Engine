package com.ridematching.location.adapters.out.redis;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.location.application.LocationIngestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Redis adapter against a real Redis, because the parts worth testing here are
 * exactly the parts a fake cannot model: {@code GEOADD} argument order, the distance units
 * {@code GEOSEARCH} returns, and whether a batch write really is one round trip.
 *
 * <p>Skipped automatically when no container runtime is reachable, so a machine without a
 * mounted Podman socket still gets a green build rather than a confusing failure. See
 * ADR-0007 for the socket mount.
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
class RedisDriverLocationIndexIT {

    static boolean containerRuntimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final Coordinates TAHRIR = new Coordinates(30.0444, 31.2357);

    private RedisDriverLocationIndex index;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();

        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        index = new RedisDriverLocationIndex(redis, new LocationIngestProperties());
    }

    private DriverLocation at(DriverId id, double lat, double lng, Instant when) {
        return new DriverLocation(id, new Coordinates(lat, lng), 90.0, 40.0, when);
    }

    @Test
    @DisplayName("a batch write lands in the geo index and is searchable by radius")
    void batchWriteIsSearchable() {
        DriverId near = DriverId.newId();
        DriverId far = DriverId.newId();
        Instant now = Instant.now();

        index.upsertAll(List.of(
                at(near, 30.0450, 31.2360, now),      // ~70m away
                at(far, 30.2000, 31.2357, now)));     // ~17km away

        List<NearbyDriver> results = index.search(TAHRIR, 2_000, 10);

        assertThat(results).extracting(nearby -> nearby.driverId()).containsExactly(near);
    }

    @Test
    @DisplayName("results come back nearest first")
    void resultsAreOrderedByDistance() {
        Instant now = Instant.now();
        DriverId closest = DriverId.newId();
        DriverId middle = DriverId.newId();
        DriverId furthest = DriverId.newId();

        index.upsertAll(List.of(
                at(furthest, 30.0500, 31.2357, now),
                at(closest, 30.0445, 31.2357, now),
                at(middle, 30.0470, 31.2357, now)));

        List<NearbyDriver> results = index.search(TAHRIR, 5_000, 10);

        assertThat(results).extracting(nearby -> nearby.driverId())
                .containsExactly(closest, middle, furthest);
        assertThat(results.get(0).distanceMeters()).isLessThan(results.get(1).distanceMeters());
    }

    @Test
    @DisplayName("latitude and longitude survive the round trip in the right order")
    void coordinatesAreNotTransposed() {
        // Redis takes longitude first; a transposition is silent and would put every driver
        // in the wrong hemisphere. Cairo transposed lands in the Indian Ocean.
        DriverId driver = DriverId.newId();
        index.upsertAll(List.of(at(driver, 30.0444, 31.2357, Instant.now())));

        NearbyDriver found = index.search(TAHRIR, 1_000, 1).get(0);

        assertThat(found.coordinates().latitude()).isCloseTo(30.0444, org.assertj.core.data.Offset.offset(0.001));
        assertThat(found.coordinates().longitude()).isCloseTo(31.2357, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("the search limit is respected")
    void limitCapsResults() {
        Instant now = Instant.now();
        for (int i = 0; i < 10; i++) {
            index.upsertAll(List.of(at(DriverId.newId(), 30.0444 + i * 0.0005, 31.2357, now)));
        }

        assertThat(index.search(TAHRIR, 10_000, 3)).hasSize(3);
    }

    @Test
    @DisplayName("an upsert for an existing driver moves them rather than duplicating")
    void upsertMovesExistingDriver() {
        DriverId driver = DriverId.newId();
        Instant now = Instant.now();

        index.upsertAll(List.of(at(driver, 30.0445, 31.2357, now)));
        index.upsertAll(List.of(at(driver, 30.0600, 31.2357, now.plusSeconds(1))));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search(TAHRIR, 500, 10)).isEmpty();
        assertThat(index.search(TAHRIR, 5_000, 10)).hasSize(1);
    }

    @Test
    @DisplayName("going offline removes the driver from the index entirely")
    void offlineRemovesDriver() {
        DriverId driver = DriverId.newId();
        index.markOnline(driver);
        index.upsertAll(List.of(at(driver, 30.0445, 31.2357, Instant.now())));
        assertThat(index.size()).isEqualTo(1);

        index.markOffline(driver);

        assertThat(index.size()).isZero();
        assertThat(index.search(TAHRIR, 5_000, 10)).isEmpty();
        assertThat(redis.hasKey("driver:" + driver + ":state")).isFalse();
    }

    @Test
    @DisplayName("the reaper evicts only drivers older than the cutoff")
    void evictsOnlyStaleDrivers() {
        Instant now = Instant.now();
        DriverId fresh = DriverId.newId();
        DriverId ghost = DriverId.newId();

        index.upsertAll(List.of(
                at(fresh, 30.0445, 31.2357, now),
                at(ghost, 30.0446, 31.2357, now.minus(Duration.ofMinutes(5)))));

        int evicted = index.evictStaleOlderThan(now.minus(Duration.ofSeconds(30)));

        assertThat(evicted).isEqualTo(1);
        assertThat(index.search(TAHRIR, 5_000, 10))
                .extracting(nearby -> nearby.driverId())
                .containsExactly(fresh);
    }

    @Test
    @DisplayName("an empty batch is a no-op, not an error")
    void emptyBatchIsSafe() {
        index.upsertAll(List.of());

        assertThat(index.size()).isZero();
    }
}
