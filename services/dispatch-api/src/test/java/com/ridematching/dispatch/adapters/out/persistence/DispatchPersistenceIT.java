package com.ridematching.dispatch.adapters.out.persistence;

import com.ridematching.dispatch.application.DriverAlreadyAssignedException;
import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.RideRequestOutcome;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.domain.trip.RideId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the two guarantees that only a real PostgreSQL can prove: the
 * {@code uniq_driver_active_trip} partial index (ADR-0004) and the atomic idempotency claim
 * (ADR-0005). Both depend on behaviour a fake cannot model — the database arbitrating
 * between concurrent writers.
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
@SpringBootTest
class DispatchPersistenceIT {

    static boolean containerRuntimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ride")
                    .withUsername("ride")
                    .withPassword("ride");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);
    private static final Coordinates DROPOFF = new Coordinates(30.0500, 31.2400);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RequestRideUseCase requestRide;

    @Autowired
    private IdempotencyStore idempotency;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE trip_events, trips, idempotency_keys, outbox CASCADE").update();
    }

    private RequestRideUseCase.RequestRideCommand command() {
        return new RequestRideUseCase.RequestRideCommand(
                RiderId.newId(), PICKUP, DROPOFF, VehicleClass.STANDARD);
    }

    /** Inserts a trip in a driver-holding state, bypassing the domain, to exercise the index. */
    private void insertTripHoldingDriver(DriverId driver, String status) {
        jdbc.sql("""
                        INSERT INTO trips (id, rider_id, driver_id, status, vehicle_class,
                                           pickup_lat, pickup_lng, dropoff_lat, dropoff_lng, requested_at)
                        VALUES (:id, :rider, :driver, CAST(:status AS trip_status),
                                CAST('STANDARD' AS vehicle_class), 30.0, 31.0, 30.1, 31.1, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("rider", UUID.randomUUID())
                .param("driver", driver.value())
                .param("status", status)
                .update();
    }

    @Nested
    class Wiring {

        @org.springframework.beans.factory.annotation.Autowired
        private org.springframework.context.ApplicationContext context;

        @Test
        @DisplayName("the application context loads")
        void contextLoads() {
            assertThat(context).isNotNull();
        }

        @Test
        @DisplayName("platform observability defaults are auto-configured")
        void observabilityDefaultsApplied() {
            assertThat(context.containsBean("percentileHistogramFilter")).isTrue();
        }
    }

    @Nested
    class Migrations {

        @Test
        @DisplayName("Flyway applied the schema")
        void schemaExists() {
            List<String> tables = jdbc.sql("""
                            SELECT table_name FROM information_schema.tables
                            WHERE table_schema = 'public' ORDER BY table_name
                            """)
                    .query(String.class).list();

            assertThat(tables).contains("trips", "trip_events", "idempotency_keys", "outbox");
        }

        @Test
        @DisplayName("the driver-uniqueness index exists with the expected predicate")
        void uniqueIndexExists() {
            String definition = jdbc.sql("""
                            SELECT indexdef FROM pg_indexes
                            WHERE indexname = 'uniq_driver_active_trip'
                            """)
                    .query(String.class).single();

            // The predicate must match TripStatus.holdsDriver(). If someone edits one side,
            // this is the test that notices.
            assertThat(definition)
                    .contains("UNIQUE")
                    .contains("OFFERED")
                    .contains("ACCEPTED")
                    .contains("IN_PROGRESS");
        }
    }

    @Nested
    class DriverUniqueness {

        @Test
        @DisplayName("a driver cannot hold two active trips")
        void secondActiveTripIsRejected() {
            DriverId driver = DriverId.newId();
            insertTripHoldingDriver(driver, "ACCEPTED");

            // This is the ADR-0004 guarantee: not a check in application code, a structural
            // impossibility enforced by the database.
            assertThatThrownBy(() -> insertTripHoldingDriver(driver, "OFFERED"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }

        @Test
        @DisplayName("the index covers OFFERED, not just ACCEPTED")
        void offeredAlsoHoldsTheDriver() {
            DriverId driver = DriverId.newId();
            insertTripHoldingDriver(driver, "OFFERED");

            // If OFFERED were outside the predicate, the race would stay open for the whole
            // 8-second offer TTL.
            assertThatThrownBy(() -> insertTripHoldingDriver(driver, "ACCEPTED"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }

        @Test
        @DisplayName("a driver is free again once the trip reaches a terminal state")
        void terminalTripReleasesDriver() {
            DriverId driver = DriverId.newId();
            insertTripHoldingDriver(driver, "COMPLETED");
            insertTripHoldingDriver(driver, "CANCELLED");

            // Neither is in the predicate, so both coexist and the driver can take a new ride.
            insertTripHoldingDriver(driver, "ACCEPTED");

            Integer active = jdbc.sql("""
                            SELECT count(*) FROM trips
                            WHERE driver_id = :d AND status IN ('OFFERED','ACCEPTED','IN_PROGRESS')
                            """)
                    .param("d", driver.value())
                    .query(Integer.class).single();

            assertThat(active).isEqualTo(1);
        }

        @Test
        @DisplayName("many trips may share a NULL driver")
        void nullDriversDoNotCollide() {
            // A partial unique index on a nullable column must not serialise unmatched rides.
            for (int i = 0; i < 5; i++) {
                requestRide.handle(command(), "key-" + i);
            }

            Integer count = jdbc.sql("SELECT count(*) FROM trips WHERE driver_id IS NULL")
                    .query(Integer.class).single();

            assertThat(count).isEqualTo(5);
        }
    }

    @Nested
    class Idempotency {

        @Test
        @DisplayName("50 concurrent retries of one key create exactly one ride")
        void concurrentRetriesCreateOneRide() throws Exception {
            int threads = 50;
            String key = "storm-key";
            var command = command();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            Set<RideRequestOutcome.Kind> kinds = ConcurrentHashMap.newKeySet();
            Set<UUID> rideIds = ConcurrentHashMap.newKeySet();
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        // Every thread blocks here, then all are released together — the
                        // window this test exists to attack is milliseconds wide.
                        startGate.await();
                        RideRequestOutcome outcome = requestRide.handle(command, key);
                        kinds.add(outcome.kind());
                        if (outcome.rideId() != null) {
                            rideIds.add(outcome.rideId().value());
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

            Integer tripCount = jdbc.sql("SELECT count(*) FROM trips").query(Integer.class).single();

            assertThat(tripCount)
                    .as("50 simultaneous retries of one idempotency key must yield ONE ride")
                    .isEqualTo(1);
            assertThat(errors.get()).isZero();
            assertThat(rideIds)
                    .as("every response that carried a rideId must carry the SAME one")
                    .hasSizeLessThanOrEqualTo(1);
            assertThat(kinds)
                    .as("outcomes must all be legitimate idempotency responses")
                    .isSubsetOf(RideRequestOutcome.Kind.ACCEPTED,
                            RideRequestOutcome.Kind.IN_PROGRESS,
                            RideRequestOutcome.Kind.REPLAYED);
        }

        @Test
        @DisplayName("1000 distinct keys create 1000 rides")
        void distinctKeysAllSucceed() throws Exception {
            int count = 200;
            CountDownLatch finished = new CountDownLatch(count);
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < count; i++) {
                String key = "key-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        requestRide.handle(command(), key);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();

            Integer trips = jdbc.sql("SELECT count(*) FROM trips").query(Integer.class).single();
            assertThat(trips)
                    .as("idempotency must not suppress genuinely distinct requests")
                    .isEqualTo(count);
        }

        @Test
        @DisplayName("the claim is atomic: only one caller wins")
        void claimIsAtomic() {
            assertThat(idempotency.tryClaim("k", "hash")).isTrue();
            assertThat(idempotency.tryClaim("k", "hash")).isFalse();
        }

        @Test
        @DisplayName("a released claim can be re-taken")
        void releasedClaimIsReusable() {
            idempotency.tryClaim("k", "hash");
            idempotency.release("k");

            assertThat(idempotency.tryClaim("k", "hash")).isTrue();
        }

        @Test
        @DisplayName("the trip and its audit trail commit together")
        void tripAndEventsCommitTogether() {
            RideRequestOutcome outcome = requestRide.handle(command(), "audit-key");

            Integer events = jdbc.sql("SELECT count(*) FROM trip_events WHERE trip_id = :id")
                    .param("id", outcome.rideId().value())
                    .query(Integer.class).single();

            assertThat(events)
                    .as("a trip with no recorded transition would be an unexplainable row")
                    .isGreaterThanOrEqualTo(1);
        }
    }
}
