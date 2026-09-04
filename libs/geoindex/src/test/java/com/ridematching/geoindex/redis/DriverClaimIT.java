package com.ridematching.geoindex.redis;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verification section of ADR-0004, made executable.
 *
 * <p>This is the test the whole project is built around. Everything else can be argued about;
 * this either passes or the central claim in the README is false.
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
class DriverClaimIT {

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

    private static final Duration TTL = Duration.ofSeconds(10);

    private RedisKeys keys;
    private StringRedisTemplate redis;
    private RedisDriverClaimStore claims;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();

        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        keys = new RedisKeys("test");
        claims = new RedisDriverClaimStore(redis, keys);
    }

    /** Puts a driver into the index as AVAILABLE, the way location-service would. */
    private DriverId availableDriver() {
        DriverId driver = DriverId.newId();
        redis.opsForHash().put(keys.driverState(driver), "status", "AVAILABLE");
        return driver;
    }

    @Nested
    class SingleClaim {

        @Test
        @DisplayName("claiming an available driver succeeds and returns a fence token")
        void claimSucceeds() {
            DriverId driver = availableDriver();

            ClaimResult result = claims.claim(driver, "offer-1", TTL);

            assertThat(result.won()).isTrue();
            assertThat(result.fence()).isPresent();
            assertThat(claims.statusOf(driver)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("a second claim on the same driver is refused")
        void secondClaimIsRefused() {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", TTL);

            ClaimResult second = claims.claim(driver, "offer-2", TTL);

            assertThat(second.won()).isFalse();
            // Refused on status, because the first claim already flipped it to RESERVED.
            assertThat(second.reason()).isIn(ClaimResult.NOT_AVAILABLE, ClaimResult.ALREADY_CLAIMED);
        }

        @Test
        @DisplayName("an unknown driver is refused rather than claimed")
        void unknownDriverIsRefused() {
            // A driver absent from the index went offline or was reaped. Claiming them would
            // dispatch an offer nobody receives and burn the whole offer TTL.
            ClaimResult result = claims.claim(DriverId.newId(), "offer-1", TTL);

            assertThat(result.won()).isFalse();
            assertThat(result.reason()).isEqualTo(ClaimResult.UNKNOWN_DRIVER);
        }

        @Test
        @DisplayName("releasing frees the driver for another worker")
        void releaseFreesDriver() {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", TTL);

            assertThat(claims.release(driver, "offer-1")).isTrue();
            assertThat(claims.statusOf(driver)).isEqualTo("AVAILABLE");
            assertThat(claims.claim(driver, "offer-2", TTL).won()).isTrue();
        }

        @Test
        @DisplayName("a stale holder cannot release someone else's claim")
        void releaseRequiresOwnership() {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", TTL);

            // Without the token check, a worker whose claim already expired could delete the
            // claim of whoever legitimately took the driver next.
            assertThat(claims.release(driver, "offer-SOMEONE-ELSE")).isFalse();
            assertThat(claims.statusOf(driver)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("a claim expires on its own if the worker dies")
        void claimExpires() throws Exception {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", Duration.ofMillis(300));

            // The TTL is the safety valve: a worker that dies between claiming and dispatching
            // must not strand the driver forever.
            Thread.sleep(500);

            assertThat(redis.hasKey(keys.claim(driver))).isFalse();
        }
    }

    @Nested
    class FencingTokens {

        @Test
        @DisplayName("fence tokens increase monotonically across claims")
        void tokensAreMonotonic() {
            DriverId a = availableDriver();
            DriverId b = availableDriver();
            DriverId c = availableDriver();

            long first = claims.claim(a, "o1", TTL).fenceToken();
            long second = claims.claim(b, "o2", TTL).fenceToken();
            long third = claims.claim(c, "o3", TTL).fenceToken();

            // Monotonicity is what lets a resource reject a write from a worker whose lease
            // lapsed while it was paused — the failure plain lease locks permit.
            assertThat(first).isLessThan(second);
            assertThat(second).isLessThan(third);
        }

        @Test
        @DisplayName("a re-claim after release carries a strictly higher token")
        void reclaimAdvancesTheToken() {
            DriverId driver = availableDriver();
            long firstFence = claims.claim(driver, "o1", TTL).fenceToken();
            claims.release(driver, "o1");

            long secondFence = claims.claim(driver, "o2", TTL).fenceToken();

            assertThat(secondFence).isGreaterThan(firstFence);
            assertThat(claims.fenceOf(driver)).contains(secondFence);
        }
    }

    @Nested
    class Contention {

        @Test
        @DisplayName("200 threads racing for one driver: exactly one wins")
        void exactlyOneWinnerUnderContention() throws Exception {
            int threads = 200;
            DriverId driver = availableDriver();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            AtomicInteger winners = new AtomicInteger();
            Set<Long> fences = ConcurrentHashMap.newKeySet();
            Set<String> lossReasons = ConcurrentHashMap.newKeySet();
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                String token = "offer-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        // Every thread parks here so they are released simultaneously. The
                        // window between HGET and HSET is microseconds; this is how you
                        // actually aim at it.
                        startGate.await();
                        ClaimResult result = claims.claim(driver, token, TTL);
                        if (result.won()) {
                            winners.incrementAndGet();
                            fences.add(result.fenceToken());
                        } else {
                            lossReasons.add(result.reason());
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

            assertThat(winners.get())
                    .as("200 workers raced for one driver; exactly one must hold the claim")
                    .isEqualTo(1);
            assertThat(fences).hasSize(1);
            assertThat(errors.get()).isZero();
            assertThat(lossReasons)
                    .as("every loser must get a clean refusal, not an exception")
                    .isSubsetOf(ClaimResult.NOT_AVAILABLE, ClaimResult.ALREADY_CLAIMED);
            assertThat(claims.statusOf(driver)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("100 drivers claimed concurrently: every claim succeeds independently")
        void independentDriversDoNotBlockEachOther() throws Exception {
            int count = 100;
            List<DriverId> drivers = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> availableDriver())
                    .toList();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(count);
            AtomicInteger winners = new AtomicInteger();
            Set<Long> fences = ConcurrentHashMap.newKeySet();

            for (DriverId driver : drivers) {
                Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                        if (claims.claim(driver, "offer-" + UUID.randomUUID(), TTL).won()) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

            // Contention on one driver must not serialise claims on unrelated drivers.
            assertThat(winners.get()).isEqualTo(count);
            drivers.forEach(d -> fences.add(claims.fenceOf(d).orElseThrow()));
            assertThat(fences)
                    .as("every driver must have a distinct fence token")
                    .hasSize(count);
        }

        @Test
        @DisplayName("after the winner releases, exactly one of the next wave wins")
        void releaseAllowsExactlyOneSuccessor() throws Exception {
            DriverId driver = availableDriver();
            claims.claim(driver, "winner", TTL);
            claims.release(driver, "winner");

            int threads = 50;
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            AtomicInteger winners = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                String token = "wave2-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                        if (claims.claim(driver, token, TTL).won()) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(winners.get()).isEqualTo(1);
        }
    }
}
