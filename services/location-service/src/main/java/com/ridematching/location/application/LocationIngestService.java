package com.ridematching.location.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.location.application.port.DriverLocationIndex;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ingest hot path: validate, throttle, buffer, flush.
 *
 * <p>Roughly 10,000 frames per second pass through here at target load, so the work done per
 * frame is deliberately small — a bounds check, one distance calculation, and an enqueue. The
 * Redis round trip happens on a separate flush, never on the caller's thread.
 *
 * <p>Constructed with plain arguments rather than annotated for injection so it can be unit
 * tested against a fake index and a {@code SimpleMeterRegistry} with no Spring context; the
 * Spring wiring lives in {@link LocationIngestConfiguration}.
 */
public class LocationIngestService {

    private final DriverLocationIndex index;
    private final LocationIngestProperties properties;
    private final Clock clock;

    /**
     * Last position actually written, per driver.
     *
     * <p>Per-instance rather than shared, which is correct because a driver holds one
     * WebSocket to one instance: that instance sees every frame for that driver and nobody
     * else sees any. A shared cache would add a network hop to the hottest path in the system
     * to synchronise state only one node can observe.
     */
    private final Map<DriverId, DriverLocation> lastPersisted = new ConcurrentHashMap<>();

    private final BlockingQueue<DriverLocation> buffer;

    private final Counter accepted;
    private final Counter throttled;
    private final Counter rejectedSkew;
    private final Counter dropped;
    private final Timer flushTimer;

    public LocationIngestService(DriverLocationIndex index,
                                 LocationIngestProperties properties,
                                 MeterRegistry meters,
                                 Clock clock) {
        this.index = index;
        this.properties = properties;
        this.clock = clock;
        this.buffer = new ArrayBlockingQueue<>(properties.getBufferCapacity());

        this.accepted = Counter.builder("location.frames.accepted")
                .description("Frames queued for persistence").register(meters);
        this.throttled = Counter.builder("location.frames.throttled")
                .description("Frames skipped because the driver barely moved").register(meters);
        this.rejectedSkew = Counter.builder("location.frames.rejected")
                .tag("reason", "clock_skew").register(meters);
        this.dropped = Counter.builder("location.frames.dropped")
                .description("Frames shed because the write buffer was full").register(meters);
        this.flushTimer = Timer.builder("location.ingest.duration")
                .description("Time to flush one batch to the index").register(meters);

        // An explicit lambda rather than Collection::size. A method reference passes the
        // queue as the receiver, and the IDE's null analysis cannot prove that satisfies
        // Micrometer's @NonNull functional descriptor — a false positive, but a permanent
        // warning teaches you to stop reading the warnings panel.
        Gauge.builder("location.buffer.depth", this.buffer, queue -> (double) queue.size())
                .description("Frames waiting to be written to the index")
                .register(meters);
    }

    /**
     * Handles one frame. Returns whether it was queued for persistence.
     *
     * <p>Never blocks and never throws on a full buffer — the caller is a WebSocket read loop,
     * and stalling it would apply backpressure to a driver who cannot slow down anyway.
     */
    public boolean ingest(DriverLocation frame) {
        if (frame.isSkewedBeyond(clock.instant(), properties.getMaxClockSkew())) {
            rejectedSkew.increment();
            return false;
        }

        if (shouldThrottle(frame)) {
            throttled.increment();
            return false;
        }

        // Recorded as persisted at enqueue time, not at flush time. The alternative lets a
        // burst of frames for one driver all pass the throttle while the batch is in flight.
        lastPersisted.put(frame.driverId(), frame);

        if (!buffer.offer(frame)) {
            // Drop-oldest: the discarded frame is by definition more stale than this one.
            buffer.poll();
            dropped.increment();
            buffer.offer(frame);
        }

        accepted.increment();
        return true;
    }

    private boolean shouldThrottle(DriverLocation frame) {
        DriverLocation previous = lastPersisted.get(frame.driverId());
        if (previous == null) {
            return false;
        }

        boolean intervalElapsed = !java.time.Duration
                .between(previous.recordedAt(), frame.recordedAt())
                .minus(properties.getMaxWriteInterval())
                .isNegative();
        if (intervalElapsed) {
            return false;
        }

        return frame.distanceMetersTo(previous) < properties.getMinMovementMeters();
    }

    /** Drains up to one batch and writes it. Returns the number of frames written. */
    public int flush() {
        List<DriverLocation> batch = new ArrayList<>(properties.getFlushBatchSize());
        buffer.drainTo(batch, properties.getFlushBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        flushTimer.record(() -> index.upsertAll(batch));
        return batch.size();
    }

    /** A driver connected. */
    public void onConnect(DriverId driverId) {
        index.markOnline(driverId);
    }

    /** A driver disconnected: forget their throttle state and drop them from the index. */
    public void onDisconnect(DriverId driverId) {
        lastPersisted.remove(driverId);
        index.markOffline(driverId);
    }

    /** Frames waiting to be written. */
    public int bufferDepth() {
        return buffer.size();
    }

    /** Drivers this instance is tracking for throttling purposes. */
    public int trackedDrivers() {
        return lastPersisted.size();
    }
}
