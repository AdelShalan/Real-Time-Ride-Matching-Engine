package com.ridematching.dispatch.application.port;

import java.util.Optional;

/**
 * Storage for idempotency keys (ADR-0005).
 *
 * <p>Backed by PostgreSQL rather than Redis on purpose: this is a correctness mechanism, and
 * it has to survive a cache flush.
 */
public interface IdempotencyStore {

    /**
     * Attempts to claim {@code key} for a new request.
     *
     * <p>Must be a single atomic statement — an {@code INSERT ... ON CONFLICT DO NOTHING} —
     * not a read followed by a write. Two simultaneous retries both call this; the database
     * decides which one wins, and there is no window in which both observe "not found".
     *
     * @return true if this caller claimed the key and should do the work
     */
    boolean tryClaim(String key, String requestHash);

    /** Reads an existing record. Empty only if the key was never claimed or has expired. */
    Optional<IdempotencyRecord> find(String key);

    /** Stores the response so a later retry replays it verbatim. */
    void complete(String key, java.util.UUID rideId, int responseStatus, String responseBody);

    /** Releases a claim whose work failed, so the client's retry can genuinely retry. */
    void release(String key);

    /** Removes expired keys. Returns how many went. */
    int purgeExpired();
}
