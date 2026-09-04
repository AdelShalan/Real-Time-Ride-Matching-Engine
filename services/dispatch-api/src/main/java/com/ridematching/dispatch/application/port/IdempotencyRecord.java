package com.ridematching.dispatch.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * A stored idempotency key.
 *
 * @param key          the client-supplied key
 * @param requestHash  hash of the request that first claimed it
 * @param inProgress   whether the original request is still running
 * @param rideId       the ride created, once known
 * @param responseStatus stored HTTP status, once complete
 * @param responseBody stored response body, replayed verbatim on retry
 */
public record IdempotencyRecord(
        String key,
        String requestHash,
        boolean inProgress,
        UUID rideId,
        Integer responseStatus,
        String responseBody) {

    public boolean matches(String candidateHash) {
        return requestHash.equals(candidateHash);
    }

    public Optional<String> body() {
        return Optional.ofNullable(responseBody);
    }
}
