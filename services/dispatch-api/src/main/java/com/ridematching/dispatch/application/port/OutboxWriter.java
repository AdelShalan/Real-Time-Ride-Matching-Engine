package com.ridematching.dispatch.application.port;

import com.ridematching.events.DomainEvent;

/**
 * Records an event for publication, in the caller's transaction (ADR-0003).
 *
 * <p>This exists because producing to Kafka and committing to PostgreSQL cannot be one atomic
 * operation. Publishing first risks an event for a trip that was never saved; committing first
 * risks a saved trip nobody is told about. Writing the event as a row in the same transaction
 * as the state change removes the choice: either both land or neither does.
 */
public interface OutboxWriter {

    /** Appends {@code event} to the outbox. Must run inside the caller's transaction. */
    void append(DomainEvent event);
}
