package com.ridematching.location.adapters.out.redis;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.DriverStatus;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.location.application.LocationIngestProperties;
import com.ridematching.location.application.port.DriverLocationIndex;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Redis implementation of the live driver index (ADR-0002).
 *
 * <p>Two keys, both hash-tagged on the city so a cluster keeps them in one slot — {@code
 * GEOSEARCH} cannot span slots, and neither can the Lua claim script that the matching engine
 * runs against the same namespace:
 *
 * <ul>
 *   <li>{@code geo:drivers:{city}} — the geospatial sorted set</li>
 *   <li>{@code driver:seen:{city}} — a sorted set scored by last-report epoch millis, which
 *       turns "who has gone quiet?" into a single {@code ZRANGEBYSCORE} instead of a scan</li>
 * </ul>
 */
public class RedisDriverLocationIndex implements DriverLocationIndex {

    private final StringRedisTemplate redis;
    private final String geoKey;
    private final String seenKey;

    public RedisDriverLocationIndex(StringRedisTemplate redis, LocationIngestProperties properties) {
        this.redis = redis;
        this.geoKey = "geo:drivers:{%s}".formatted(properties.getCity());
        this.seenKey = "driver:seen:{%s}".formatted(properties.getCity());
    }

    @Override
    public void upsertAll(List<DriverLocation> locations) {
        if (locations.isEmpty()) {
            return;
        }

        // One GEOADD and one ZADD for the whole batch, whatever its size. This is the
        // difference between ~20 round trips per second and ~10,000.
        Map<String, Point> positions = new HashMap<>(locations.size());
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> seen =
                new java.util.HashSet<>(locations.size());

        for (DriverLocation location : locations) {
            String member = location.driverId().toString();
            // Redis takes longitude first. Swapping these is silent and puts every driver in
            // the wrong hemisphere, so it is worth stating.
            positions.put(member, new Point(location.coordinates().longitude(),
                    location.coordinates().latitude()));
            seen.add(org.springframework.data.redis.core.ZSetOperations.TypedTuple.of(
                    member, (double) location.recordedAt().toEpochMilli()));
        }

        redis.opsForGeo().add(geoKey, positions);
        redis.opsForZSet().add(seenKey, seen);
    }

    @Override
    public void markOnline(DriverId driverId) {
        redis.opsForHash().put(stateKey(driverId), "status", DriverStatus.AVAILABLE.name());
    }

    @Override
    public void markOffline(DriverId driverId) {
        String member = driverId.toString();
        redis.opsForZSet().remove(geoKey, member);
        redis.opsForZSet().remove(seenKey, member);
        redis.delete(stateKey(driverId));
    }

    @Override
    public List<NearbyDriver> search(Coordinates center, double radiusMeters, int limit) {
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs
                .newGeoSearchArgs()
                .includeCoordinates()
                .includeDistance()
                .sortAscending()
                .limit(limit);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                geoKey,
                GeoReference.fromCoordinate(new Point(center.longitude(), center.latitude())),
                new Distance(radiusMeters, Metrics.NEUTRAL),
                args);

        if (results == null) {
            return List.of();
        }

        List<NearbyDriver> nearby = new ArrayList<>(results.getContent().size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            RedisGeoCommands.GeoLocation<String> location = result.getContent();
            Point point = location.getPoint();
            nearby.add(new NearbyDriver(
                    new DriverId(UUID.fromString(location.getName())),
                    new Coordinates(point.getY(), point.getX()),
                    result.getDistance().getValue()));
        }
        return nearby;
    }

    @Override
    public int evictStaleOlderThan(Instant cutoff) {
        Set<String> stale = redis.opsForZSet()
                .rangeByScore(seenKey, 0, (double) cutoff.toEpochMilli());
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        Object[] members = stale.toArray();
        redis.opsForZSet().remove(geoKey, members);
        redis.opsForZSet().remove(seenKey, members);
        stale.forEach(id -> redis.delete("driver:" + id + ":state"));
        return stale.size();
    }

    @Override
    public long size() {
        Long count = redis.opsForZSet().zCard(geoKey);
        return count == null ? 0L : count;
    }

    private String stateKey(DriverId driverId) {
        return "driver:" + driverId + ":state";
    }
}
