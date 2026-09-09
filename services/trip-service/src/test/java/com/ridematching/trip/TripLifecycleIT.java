package com.ridematching.trip;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripStatus;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import com.ridematching.trip.application.ClaimReconciler;
import com.ridematching.domain.trip.IllegalTripTransitionException;
import com.ridematching.trip.application.ConcurrentTripModificationException;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.TripLifecycle;
import com.ridematching.trip.application.port.TripStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle against the real database and the real Redis.
 *
 * <p>Three things here cannot be tested with fakes, and they are the three the design rests on:
 * that {@code uniq_driver_active_trip} physically refuses a second active trip for one driver,
 * that the Lua release script only frees a claim to the token that owns it, and that the
 * reconciler can tell a stranded claim from one that is merely in flight. Each of those lives
 * in the engine, not in Java.
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
@SpringBootTest
class TripLifecycleIT {

    static boolean containerRuntimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // @SuppressWarnings("resource") is required, not decorative: the compiler sees a Closeable
    // assigned to a field and never closed. Testcontainers' JUnit extension owns the lifecycle
    // of @Container fields and stops them after the class, so there is no leak.
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ride").withUsername("ride").withPassword("ride");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Park both background loops. These tests drive the lifecycle directly and truncate
        // between cases; a publisher polling the same tables mid-TRUNCATE fails with a
        // rollback error that says nothing about the code under test. The reconciler is
        // invoked explicitly where it is the subject.
        registry.add("ridematching.trip.outbox-interval", () -> "PT1H");
        registry.add("ridematching.trip.reconcile-interval", () -> "PT1H");
    }

    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);
    private static final Coordinates DROPOFF = new Coordinates(30.0561, 31.2394);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TripStore trips;

    @Autowired
    private TripLifecycle lifecycle;

    @Autowired
    private ClaimReconciler reconciler;

    @Autowired
    private DriverClaimStore claims;

    @Autowired
    private RedisKeys keys;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE trip_events, trips, idempotency_keys, outbox CASCADE").update();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** Inserts a REQUESTED trip the way dispatch-api does, and returns its id. */
    private RideId givenRequestedTrip() {
        Trip trip = Trip.request(new RideId(UUID.randomUUID()), RiderId.newId(),
                PICKUP, DROPOFF, VehicleClass.STANDARD, clock);
        jdbc.sql("""
                        INSERT INTO trips (id, rider_id, status, vehicle_class,
                                           pickup_lat, pickup_lng, dropoff_lat, dropoff_lng,
                                           requested_at, version)
                        VALUES (:id, :riderId, CAST('REQUESTED' AS trip_status),
                                CAST('STANDARD' AS vehicle_class),
                                :pickupLat, :pickupLng, :dropoffLat, :dropoffLng, :requestedAt, 0)
                        """)
                .param("id", trip.id().value())
                .param("riderId", trip.riderId().value())
                .param("pickupLat", PICKUP.latitude())
                .param("pickupLng", PICKUP.longitude())
                .param("dropoffLat", DROPOFF.latitude())
                .param("dropoffLng", DROPOFF.longitude())
                .param("requestedAt", Timestamp.from(trip.requestedAt()))
                .update();
        return trip.id();
    }

    /** Puts a driver in Redis as AVAILABLE, then claims them for this ride. */
    private DriverId givenClaimedDriver(RideId rideId) {
        DriverId driverId = DriverId.newId();
        redis.opsForHash().put(keys.driverState(driverId), "status", "AVAILABLE");

        String offerToken = "offer-" + rideId;
        var claim = claims.claim(driverId, offerToken, java.time.Duration.ofSeconds(8));
        assertThat(claim.won()).isTrue();

        TripStore.Versioned loaded = trips.load(rideId).orElseThrow();
        Trip trip = loaded.trip();
        trip.beginMatching();
        trip.offerTo(driverId, claim.fenceToken(), offerToken);
        trips.save(trip, loaded.version(), null);
        return driverId;
    }

    @Nested
    @DisplayName("the full journey")
    class HappyPath {

        @Test
        @DisplayName("accept, start, complete — and the driver comes back")
        void driverIsReturnedToThePool() {
            RideId rideId = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(rideId);

            assertThat(lifecycle.accept(rideId)).isEqualTo(TripStatus.ACCEPTED);
            // Pinned: the offer TTL is gone, so nothing but an explicit release frees them now.
            assertThat(redis.getExpire(keys.claim(driverId))).isEqualTo(-1);
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");

            assertThat(lifecycle.start(rideId)).isEqualTo(TripStatus.IN_PROGRESS);
            assertThat(lifecycle.complete(rideId)).isEqualTo(TripStatus.COMPLETED);

            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
            assertThat(redis.hasKey(keys.claim(driverId))).isFalse();
        }

        @Test
        @DisplayName("writes the audit trail and the outbox row in the same transaction")
        void completionIsDurableAndAnnounced() {
            RideId rideId = givenRequestedTrip();
            givenClaimedDriver(rideId);
            lifecycle.accept(rideId);
            lifecycle.start(rideId);
            lifecycle.complete(rideId);

            Integer events = jdbc.sql(
                            "SELECT count(*) FROM trip_events WHERE trip_id = :id AND to_status = 'COMPLETED'")
                    .param("id", rideId.value()).query(Integer.class).single();
            Integer outbox = jdbc.sql(
                            "SELECT count(*) FROM outbox WHERE topic = 'ride.completed.v1'")
                    .query(Integer.class).single();

            assertThat(events).isEqualTo(1);
            assertThat(outbox).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the unique index")
    class TheGuarantee {

        @Test
        @DisplayName("refuses to let one driver hold two active trips")
        void secondActiveTripForOneDriverIsRejected() {
            RideId first = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(first);
            lifecycle.accept(first);

            // A second trip tries to take the same driver. Redis would allow it — the claim
            // has been released and re-taken in plenty of real failure modes — but the index
            // is the boundary, and this is where the attempt has to die.
            RideId second = givenRequestedTrip();
            TripStore.Versioned loaded = trips.load(second).orElseThrow();
            Trip trip = loaded.trip();
            trip.beginMatching();
            trip.offerTo(driverId, 9999L, "offer-" + second);

            assertThatThrownBy(() -> trips.save(trip, loaded.version(), null))
                    .isInstanceOf(DriverAlreadyAssignedException.class);
        }

        @Test
        @DisplayName("lets the driver be reused once the first trip has finished")
        void driverIsReusableAfterCompletion() {
            RideId first = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(first);
            lifecycle.accept(first);
            lifecycle.start(first);
            lifecycle.complete(first);

            RideId second = givenRequestedTrip();
            TripStore.Versioned loaded = trips.load(second).orElseThrow();
            Trip trip = loaded.trip();
            trip.beginMatching();
            trip.offerTo(driverId, 9999L, "offer-" + second);

            // COMPLETED is outside the index predicate, so the row no longer holds the driver.
            // The predicate and TripStatus.holdsDriver() agreeing is what makes this work.
            trips.save(trip, loaded.version(), null);
            assertThat(trips.load(second).orElseThrow().trip().status()).isEqualTo(TripStatus.OFFERED);
        }
    }

    @Nested
    @DisplayName("contention")
    class Contention {

        @Test
        @DisplayName("only one of many simultaneous accepts wins")
        void concurrentAcceptsProduceExactlyOneWinner() throws Exception {
            RideId rideId = givenRequestedTrip();
            givenClaimedDriver(rideId);

            int threads = 32;
            var start = new java.util.concurrent.CountDownLatch(1);
            var winners = new java.util.concurrent.atomic.AtomicInteger();
            var losers = new java.util.concurrent.atomic.AtomicInteger();
            var unexpected = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            // Released together, so the loads genuinely overlap. Staggered
                            // starts would let each request finish before the next began and
                            // the test would pass without ever exercising the race.
                            start.await();
                            lifecycle.accept(rideId);
                            winners.incrementAndGet();
                        } catch (ConcurrentTripModificationException
                                 | IllegalTripTransitionException expected) {
                            // Two shapes of the same loss. Whether a straggler is rejected by
                            // the version check or by the state machine depends on whether it
                            // read before or after the winner committed, and both are correct.
                            losers.incrementAndGet();
                        } catch (Throwable t) {
                            unexpected.add(t);
                        }
                    });
                }
                start.countDown();
            }

            assertThat(unexpected).isEmpty();
            assertThat(winners.get()).isEqualTo(1);
            assertThat(losers.get()).isEqualTo(threads - 1);

            // And the database agrees: one acceptance, not thirty-two.
            Integer acceptances = jdbc.sql("""
                            SELECT count(*) FROM trip_events
                            WHERE trip_id = :id AND to_status = 'ACCEPTED'
                            """)
                    .param("id", rideId.value()).query(Integer.class).single();
            assertThat(acceptances).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the reconciler")
    class Reconciliation {

        @Test
        @DisplayName("frees a driver stranded by a crash after the trip finished")
        void strandedClaimIsReclaimed() {
            RideId rideId = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(rideId);
            lifecycle.accept(rideId);
            lifecycle.start(rideId);

            // Complete the trip in the database WITHOUT going through the lifecycle: exactly
            // the state a crash between the commit and the release would leave behind. The
            // claim is pinned, so no TTL will ever clean it up.
            jdbc.sql("UPDATE trips SET status = CAST('COMPLETED' AS trip_status) WHERE id = :id")
                    .param("id", rideId.value()).update();
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");

            assertThat(reconciler.reconcile()).isEqualTo(1);
            assertThat(claims.statusOf(driverId)).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("leaves an offer in flight alone")
        void unexpiredClaimIsNotTouched() {
            // A claim taken moments ago whose match has not been recorded yet: it still has a
            // TTL, and there is no trip row holding the driver. A reconciler that looked only
            // at the database would free a driver who is about to be legitimately assigned.
            DriverId driverId = DriverId.newId();
            redis.opsForHash().put(keys.driverState(driverId), "status", "AVAILABLE");
            claims.claim(driverId, "offer-in-flight", java.time.Duration.ofSeconds(30));

            assertThat(reconciler.reconcile()).isZero();
            assertThat(claims.statusOf(driverId)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("leaves a driver who is genuinely on a trip alone")
        void activeTripIsNotReclaimed() {
            RideId rideId = givenRequestedTrip();
            DriverId driverId = givenClaimedDriver(rideId);
            lifecycle.accept(rideId);

            assertThat(reconciler.reconcile()).isZero();
            assertThat(claims.statusOf(driverId)).isEqualTo("ON_TRIP");
        }
    }
}
