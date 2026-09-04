package com.ridematching.location.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the ingest path. Every value here is a trade-off between write volume and
 * matching freshness, so all of them are configuration rather than constants — the load tests
 * sweep them.
 */
@ConfigurationProperties(prefix = "ridematching.location")
public class LocationIngestProperties {

    /**
     * A frame is discarded if the driver has moved less than this since their last persisted
     * position. At urban speeds this removes a large share of writes; set it too high and
     * candidate distances go stale, too low and the throttle stops earning its keep.
     */
    private double minMovementMeters = 25.0;

    /** A frame is always persisted if this long has passed, however little the driver moved. */
    private Duration maxWriteInterval = Duration.ofSeconds(3);

    /** Frames further from now than this — in either direction — are rejected as clock skew. */
    private Duration maxClockSkew = Duration.ofSeconds(30);

    /**
     * Bound on the write buffer. Full means drop-oldest: a stale position has no value, so
     * shedding it is correct behaviour rather than data loss.
     */
    private int bufferCapacity = 50_000;

    /** Flush when the buffer reaches this many frames. */
    private int flushBatchSize = 500;

    /** Flush at least this often, even if the batch is not full. */
    private Duration flushInterval = Duration.ofMillis(50);

    /** A driver silent for longer than this is evicted by the reaper. */
    private Duration staleAfter = Duration.ofSeconds(30);

    /** How often the reaper sweeps. */
    private Duration reaperInterval = Duration.ofSeconds(10);

    /** Redis key namespace; the hash tag keeps one city on a single cluster slot. */
    private String city = "default";

    public double getMinMovementMeters() {
        return minMovementMeters;
    }

    public void setMinMovementMeters(double minMovementMeters) {
        this.minMovementMeters = minMovementMeters;
    }

    public Duration getMaxWriteInterval() {
        return maxWriteInterval;
    }

    public void setMaxWriteInterval(Duration maxWriteInterval) {
        this.maxWriteInterval = maxWriteInterval;
    }

    public Duration getMaxClockSkew() {
        return maxClockSkew;
    }

    public void setMaxClockSkew(Duration maxClockSkew) {
        this.maxClockSkew = maxClockSkew;
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }

    public void setBufferCapacity(int bufferCapacity) {
        this.bufferCapacity = bufferCapacity;
    }

    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public void setFlushBatchSize(int flushBatchSize) {
        this.flushBatchSize = flushBatchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public Duration getReaperInterval() {
        return reaperInterval;
    }

    public void setReaperInterval(Duration reaperInterval) {
        this.reaperInterval = reaperInterval;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }
}
