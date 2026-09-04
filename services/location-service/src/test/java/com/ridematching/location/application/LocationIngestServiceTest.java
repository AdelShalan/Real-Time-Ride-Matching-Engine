package com.ridematching.location.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.geoindex.DriverLocationIndex;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ingest path with no Redis, no Spring and no containers — the whole point of putting the
 * index behind a port (ADR-0001). Runs in milliseconds.
 */
class LocationIngestServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-04T10:00:00Z");
    private static final Coordinates ORIGIN = new Coordinates(30.0444, 31.2357);

    /** Records what would have been written, so assertions are about behaviour not mocks. */
    private static final class RecordingIndex implements DriverLocationIndex {
        final List<DriverLocation> written = new ArrayList<>();
        final List<DriverId> online = new ArrayList<>();
        final List<DriverId> offline = new ArrayList<>();

        @Override
        public void upsertAll(List<DriverLocation> locations) {
            written.addAll(locations);
        }

        @Override
        public void markOnline(DriverId driverId) {
            online.add(driverId);
        }

        @Override
        public void markOffline(DriverId driverId) {
            offline.add(driverId);
        }

        @Override
        public List<NearbyDriver> search(Coordinates center, double radiusMeters, int limit) {
            return List.of();
        }

        @Override
        public int evictStaleOlderThan(Instant cutoff) {
            return 0;
        }

        @Override
        public long size() {
            return written.size();
        }
    }

    private RecordingIndex index;
    private LocationIngestProperties properties;
    private MeterRegistry meters;
    private LocationIngestService service;
    private final DriverId driver = DriverId.newId();

    @BeforeEach
    void setUp() {
        index = new RecordingIndex();
        properties = new LocationIngestProperties();
        meters = new SimpleMeterRegistry();
        service = new LocationIngestService(index, properties, meters,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    /** A position {@code metersEast} east of the origin, at {@code offset} after T0. */
    private DriverLocation frameAt(double metersEast, Duration offset) {
        // ~111,320 m per degree of longitude at the equator, scaled by cos(latitude).
        double degreesPerMeter = 1.0 / (111_320.0 * Math.cos(Math.toRadians(ORIGIN.latitude())));
        Coordinates moved = new Coordinates(
                ORIGIN.latitude(), ORIGIN.longitude() + metersEast * degreesPerMeter);
        return new DriverLocation(driver, moved, 90.0, 40.0, T0.plus(offset));
    }

    private double counter(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Nested
    class Throttling {

        @Test
        @DisplayName("the first frame from a driver is always written")
        void firstFrameAlwaysAccepted() {
            assertThat(service.ingest(frameAt(0, Duration.ZERO))).isTrue();
        }

        @Test
        @DisplayName("a driver that barely moved is throttled")
        void smallMovementIsThrottled() {
            service.ingest(frameAt(0, Duration.ZERO));

            // 10 m is below the 25 m threshold, and only 1s has passed.
            assertThat(service.ingest(frameAt(10, Duration.ofSeconds(1)))).isFalse();
            assertThat(counter("location.frames.throttled")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a driver that moved far enough is written even within the interval")
        void largeMovementIsAccepted() {
            service.ingest(frameAt(0, Duration.ZERO));

            assertThat(service.ingest(frameAt(100, Duration.ofSeconds(1)))).isTrue();
        }

        @Test
        @DisplayName("a stationary driver is still written once the max interval elapses")
        void stationaryDriverStillRefreshed() {
            service.ingest(frameAt(0, Duration.ZERO));

            // Parked, but the heartbeat must continue or the reaper would evict them.
            assertThat(service.ingest(frameAt(0, Duration.ofSeconds(3)))).isTrue();
        }

        @Test
        @DisplayName("throttling is per driver, not global")
        void throttlingIsPerDriver() {
            service.ingest(frameAt(0, Duration.ZERO));

            DriverId other = DriverId.newId();
            DriverLocation otherFrame = new DriverLocation(other, ORIGIN, 0, 0, T0);

            assertThat(service.ingest(otherFrame)).isTrue();
        }

        @Test
        @DisplayName("throttle state is measured from the last WRITTEN frame, not the last seen")
        void throttleAnchorsOnPersistedPosition() {
            service.ingest(frameAt(0, Duration.ZERO));
            service.ingest(frameAt(10, Duration.ofSeconds(1)));   // throttled
            service.ingest(frameAt(20, Duration.ofSeconds(2)));   // still < 25m from origin

            // Had the anchor moved to the throttled frames, this would look like 10m of
            // movement and be dropped — the driver would drift 20m at a time, forever.
            assertThat(service.ingest(frameAt(30, Duration.ofSeconds(2)))).isTrue();
        }
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("a frame from the far past is rejected as clock skew")
        void rejectsStaleFrame() {
            DriverLocation old = new DriverLocation(driver, ORIGIN, 0, 0, T0.minusSeconds(120));

            assertThat(service.ingest(old)).isFalse();
            assertThat(counter("location.frames.rejected")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a future-dated frame is rejected too")
        void rejectsFutureFrame() {
            // A device with a fast clock would otherwise win "most recent" forever.
            DriverLocation future = new DriverLocation(driver, ORIGIN, 0, 0, T0.plusSeconds(120));

            assertThat(service.ingest(future)).isFalse();
        }
    }

    @Nested
    class Buffering {

        @Test
        @DisplayName("frames are buffered, not written on the caller's thread")
        void ingestDoesNotWriteSynchronously() {
            service.ingest(frameAt(0, Duration.ZERO));

            assertThat(index.written)
                    .as("the WebSocket read loop must not wait for Redis")
                    .isEmpty();
            assertThat(service.bufferDepth()).isEqualTo(1);
        }

        @Test
        @DisplayName("flush drains the buffer into the index")
        void flushWrites() {
            service.ingest(frameAt(0, Duration.ZERO));
            service.ingest(frameAt(100, Duration.ofSeconds(1)));

            assertThat(service.flush()).isEqualTo(2);
            assertThat(index.written).hasSize(2);
            assertThat(service.bufferDepth()).isZero();
        }

        @Test
        @DisplayName("flushing an empty buffer does nothing")
        void flushEmptyIsNoOp() {
            assertThat(service.flush()).isZero();
            assertThat(index.written).isEmpty();
        }

        @Test
        @DisplayName("a flush never exceeds the configured batch size")
        void flushRespectsBatchSize() {
            properties.setFlushBatchSize(3);
            properties.setMinMovementMeters(0);
            for (int i = 0; i < 10; i++) {
                service.ingest(new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0));
            }

            assertThat(service.flush()).isEqualTo(3);
            assertThat(service.bufferDepth()).isEqualTo(7);
        }

        @Test
        @DisplayName("a full buffer sheds the oldest frame instead of blocking")
        void fullBufferDropsOldest() {
            LocationIngestProperties tiny = new LocationIngestProperties();
            tiny.setBufferCapacity(2);
            tiny.setMinMovementMeters(0);
            LocationIngestService small = new LocationIngestService(index, tiny, meters,
                    Clock.fixed(T0, ZoneOffset.UTC));

            DriverLocation first = new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0);
            DriverLocation second = new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0);
            DriverLocation third = new DriverLocation(DriverId.newId(), ORIGIN, 0, 0, T0);

            small.ingest(first);
            small.ingest(second);
            small.ingest(third);

            assertThat(small.bufferDepth()).isEqualTo(2);
            assertThat(counter("location.frames.dropped")).isEqualTo(1.0);

            small.flush();
            assertThat(index.written)
                    .as("the oldest frame is the one shed")
                    .doesNotContain(first)
                    .contains(second, third);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        @DisplayName("connect registers the driver as online")
        void connectMarksOnline() {
            service.onConnect(driver);

            assertThat(index.online).containsExactly(driver);
        }

        @Test
        @DisplayName("disconnect removes the driver and forgets its throttle state")
        void disconnectClearsState() {
            service.ingest(frameAt(0, Duration.ZERO));
            assertThat(service.trackedDrivers()).isEqualTo(1);

            service.onDisconnect(driver);

            assertThat(index.offline).containsExactly(driver);
            assertThat(service.trackedDrivers())
                    .as("stale throttle entries would leak memory across reconnects")
                    .isZero();
        }

        @Test
        @DisplayName("a reconnecting driver is not throttled against their pre-disconnect position")
        void reconnectResetsThrottle() {
            service.ingest(frameAt(0, Duration.ZERO));
            service.onDisconnect(driver);

            assertThat(service.ingest(frameAt(1, Duration.ofSeconds(1)))).isTrue();
        }
    }
}
