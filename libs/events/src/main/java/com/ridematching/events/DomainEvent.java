package com.ridematching.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract every published event satisfies.
 *
 * <p>Kafka delivery is at-least-once (ADR-0003), so consumers de-duplicate on
 * {@link #eventId()}. That makes the id part of the contract rather than metadata:
 * an event without a stable id cannot be safely consumed twice.
 *
 * <p>{@link #partitionKey()} is what preserves ordering. Events sharing a key land on
 * one partition and are therefore strictly ordered relative to each other — which is why
 * ride events key on the ride id and location events key on the driver id.
 */
public interface DomainEvent {

    /** Stable, unique per event instance. Redelivery repeats it; a retry does not change it. */
    UUID eventId();

    /** When the event actually happened, not when it was published. */
    Instant occurredAt();

    /** Kafka partition key. Determines both ordering and consumer fan-out. */
    String partitionKey();

    /** Destination topic, including its version suffix (e.g. {@code ride.matched.v1}). */
    String topic();
}
