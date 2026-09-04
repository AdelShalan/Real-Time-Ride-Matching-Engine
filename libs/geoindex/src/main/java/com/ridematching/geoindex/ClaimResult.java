package com.ridematching.geoindex;

import java.util.Optional;

/**
 * Outcome of attempting to claim a driver (ADR-0004).
 *
 * @param won        whether this caller holds the claim
 * @param fenceToken monotonic token proving which claim is newest; present only when won
 * @param reason     why the claim was refused; present only when lost
 */
public record ClaimResult(boolean won, Long fenceToken, String reason) {

    /** The driver was already claimed by another worker. */
    public static final String ALREADY_CLAIMED = "ALREADY_CLAIMED";
    /** The driver is not in AVAILABLE state (on a trip, or reserved). */
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    /** No state hash: the driver is offline or was evicted as stale. */
    public static final String UNKNOWN_DRIVER = "UNKNOWN_DRIVER";

    public static ClaimResult won(long fenceToken) {
        return new ClaimResult(true, fenceToken, null);
    }

    public static ClaimResult lost(String reason) {
        return new ClaimResult(false, null, reason);
    }

    public Optional<Long> fence() {
        return Optional.ofNullable(fenceToken);
    }
}
