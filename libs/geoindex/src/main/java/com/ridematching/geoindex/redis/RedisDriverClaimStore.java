package com.ridematching.geoindex.redis;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis implementation of the driver claim (ADR-0004, layer 1).
 *
 * <p>The claim itself is a Lua script, loaded from {@code redis/claim-driver.lua}. Keeping it
 * in a file rather than a Java string keeps it readable, syntax-highlighted, and reviewable as
 * the concurrency-critical code it is.
 *
 * <p>Spring Data Redis caches the script by SHA and uses {@code EVALSHA}, so the body crosses
 * the wire once per connection rather than on every claim — which matters when this runs
 * hundreds of times a second.
 */
public class RedisDriverClaimStore implements DriverClaimStore {

    @SuppressWarnings("unchecked")
    private static final RedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>(
            readScript("redis/claim-driver.lua"), List.class);

    /**
     * Release is also a script, for the same reason the claim is: checking the token and
     * deleting the key must not be two round trips, or a claim that expires between them
     * could have its successor's marker deleted by the loser.
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
              return 0
            end
            redis.call('DEL', KEYS[2])
            redis.call('HSET', KEYS[1], 'status', 'AVAILABLE')
            return 1
            """, Long.class);

    private static final RedisScript<Long> ON_TRIP_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'status', 'ON_TRIP')
            redis.call('PERSIST', KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    public RedisDriverClaimStore(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    @Override
    public ClaimResult claim(DriverId driverId, String offerToken, Duration ttl) {
        List<?> result = redis.execute(
                CLAIM_SCRIPT,
                List.of(keys.driverState(driverId), keys.claim(driverId), keys.fenceCounter()),
                offerToken,
                String.valueOf(ttl.toMillis()));

        if (result == null || result.size() < 2) {
            // A null reply means the script did not run — treat it as a loss rather than a
            // win. Failing closed is the only safe direction for a claim.
            return ClaimResult.lost("SCRIPT_ERROR");
        }

        boolean won = toLong(result.get(0)) == 1L;
        return won
                ? ClaimResult.won(toLong(result.get(1)))
                : ClaimResult.lost(String.valueOf(result.get(1)));
    }

    @Override
    public boolean release(DriverId driverId, String offerToken) {
        Long released = redis.execute(RELEASE_SCRIPT,
                List.of(keys.driverState(driverId), keys.claim(driverId)),
                offerToken);
        return released != null && released == 1L;
    }

    @Override
    public void markOnTrip(DriverId driverId, String offerToken) {
        redis.execute(ON_TRIP_SCRIPT,
                List.of(keys.driverState(driverId), keys.claim(driverId)),
                offerToken);
    }

    @Override
    public String statusOf(DriverId driverId) {
        Object status = redis.opsForHash().get(keys.driverState(driverId), "status");
        return status == null ? null : status.toString();
    }

    @Override
    public Optional<Long> fenceOf(DriverId driverId) {
        Object fence = redis.opsForHash().get(keys.driverState(driverId), "fence");
        return fence == null ? Optional.empty() : Optional.of(Long.parseLong(fence.toString()));
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static String readScript(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load Lua script " + path, e);
        }
    }
}
