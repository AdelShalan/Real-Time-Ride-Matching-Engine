package com.ridematching.geoindex;

import com.ridematching.domain.driver.DriverId;

import java.time.Duration;

/**
 * The fast path of the two-layer driver claim (ADR-0004).
 *
 * <p>Deliberately narrow. This port cannot express "lock and then do arbitrary work" because
 * that is the pattern whose safety depends on the holder finishing before its lease expires —
 * an assumption a JVM GC pause invalidates. Callers claim, act, and either confirm or release,
 * and every durable write carries the fence token so a late writer is rejected at the resource.
 */
public interface DriverClaimStore {

    /**
     * Attempts to reserve {@code driverId} for {@code offerToken}.
     *
     * <p>Atomic: exactly one concurrent caller can win. Losers are told immediately and should
     * move to their next candidate rather than retry the same driver — retrying is how a
     * contended driver turns into a queue.
     */
    ClaimResult claim(DriverId driverId, String offerToken, Duration ttl);

    /**
     * Releases a claim, but only if {@code offerToken} still owns it.
     *
     * <p>The token check is essential: without it, a worker whose claim already expired could
     * delete the claim of whichever worker legitimately took the driver afterwards.
     */
    boolean release(DriverId driverId, String offerToken);

    /** Marks a claimed driver as on-trip once the assignment is durable. */
    void markOnTrip(DriverId driverId, String offerToken);

    /** Current status in the index, or null when the driver is unknown. */
    String statusOf(DriverId driverId);

    /** Current fence token for the driver, or empty when never claimed. */
    java.util.Optional<Long> fenceOf(DriverId driverId);
}
