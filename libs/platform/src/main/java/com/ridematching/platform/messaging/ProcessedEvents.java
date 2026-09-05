package com.ridematching.platform.messaging;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * De-duplication for at-least-once consumers (ADR-0003).
 *
 * <p>Kafka redelivers on rebalance, on a crash between processing and offset commit, and on
 * any retry. Without this, a rider gets two "driver found" notifications for one ride, and
 * billing charges twice.
 *
 * <p>Backed by Redis {@code SET NX} rather than a database table because these consumers own
 * no durable state — giving notification-service a PostgreSQL schema purely to hold event ids
 * would be a heavy price for a bookkeeping concern. The trade-off is honest: a Redis flush
 * would allow duplicates through, which for a notification is a second push message, not a
 * corrupted ledger. A consumer with money at stake should dedupe in the same transaction as
 * its write instead.
 *
 * <p>The TTL bounds memory. It must comfortably exceed the longest plausible redelivery
 * window — a consumer restarting from a lagged offset — or an old duplicate would slip past
 * an expired marker.
 */
public class ProcessedEvents {

    private final StringRedisTemplate redis;
    private final String prefix;
    private final Duration retention;

    public ProcessedEvents(StringRedisTemplate redis, String consumerGroup, Duration retention) {
        this.redis = redis;
        // Namespaced per consumer group: the same event legitimately reaches notification and
        // billing, and one must not mask the other.
        this.prefix = "processed:" + consumerGroup + ":";
        this.retention = retention;
    }

    /**
     * Marks {@code eventId} as seen, returning whether this caller is the first.
     *
     * <p>{@code SET NX} is atomic, so two consumer threads handling the same redelivered
     * record cannot both be told they are first.
     *
     * @return true if the event has not been processed before and should be handled now
     */
    public boolean markIfFirst(String eventId) {
        Boolean first = redis.opsForValue().setIfAbsent(prefix + eventId, "1", retention);
        return Boolean.TRUE.equals(first);
    }

    /**
     * Forgets an event so it can be reprocessed.
     *
     * <p>Called when handling fails: the marker is written <em>before</em> the work, so a
     * failure that left the marker in place would suppress the retry entirely and silently
     * drop the event.
     */
    public void forget(String eventId) {
        redis.delete(prefix + eventId);
    }
}
