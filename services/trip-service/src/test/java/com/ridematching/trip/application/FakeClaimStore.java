package com.ridematching.trip.application;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.DriverClaimStore;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory claim store that keeps the one rule the real one has: a release only succeeds
 * if the caller's token still owns the claim.
 *
 * <p>Written by hand rather than mocked. A mock would let a test assert that {@code release}
 * was called, which is not the property that matters — the property is that the driver ends up
 * back in the pool. Modelling the token check here means a test can assert on the resulting
 * state, and a release with the wrong token fails in the test exactly as it would in Redis.
 */
class FakeClaimStore implements DriverClaimStore {

    private final Map<DriverId, String> claims = new HashMap<>();
    private final Map<DriverId, String> statuses = new HashMap<>();
    private final Map<DriverId, Boolean> pinned = new HashMap<>();
    private long fence;

    private RuntimeException failure;

    /** Puts a driver in the index as claimable. */
    void available(DriverId driverId) {
        statuses.put(driverId, "AVAILABLE");
        claims.remove(driverId);
        pinned.remove(driverId);
    }

    /** Puts a driver in the state the matching engine leaves them in after winning a claim. */
    void reserved(DriverId driverId, String offerToken) {
        statuses.put(driverId, "RESERVED");
        claims.put(driverId, offerToken);
        pinned.put(driverId, false);
    }

    /** Makes every subsequent call throw, standing in for Redis being unreachable. */
    void failWith(RuntimeException e) {
        this.failure = e;
    }

    boolean isPinned(DriverId driverId) {
        return Boolean.TRUE.equals(pinned.get(driverId));
    }

    boolean holdsClaim(DriverId driverId) {
        return claims.containsKey(driverId);
    }

    @Override
    public ClaimResult claim(DriverId driverId, String offerToken, Duration ttl) {
        raiseIfFailing();
        if (claims.containsKey(driverId)) {
            return ClaimResult.lost(ClaimResult.ALREADY_CLAIMED);
        }
        claims.put(driverId, offerToken);
        statuses.put(driverId, "RESERVED");
        return ClaimResult.won(++fence);
    }

    @Override
    public boolean release(DriverId driverId, String offerToken) {
        raiseIfFailing();
        // The token check, which is the whole reason release is a script in the real store.
        if (!offerToken.equals(claims.get(driverId))) {
            return false;
        }
        claims.remove(driverId);
        pinned.remove(driverId);
        statuses.put(driverId, "AVAILABLE");
        return true;
    }

    @Override
    public void markOnTrip(DriverId driverId, String offerToken) {
        raiseIfFailing();
        if (!offerToken.equals(claims.get(driverId))) {
            return;
        }
        statuses.put(driverId, "ON_TRIP");
        pinned.put(driverId, true);
    }

    @Override
    public String statusOf(DriverId driverId) {
        return statuses.get(driverId);
    }

    @Override
    public Optional<Long> fenceOf(DriverId driverId) {
        return Optional.of(fence);
    }

    private void raiseIfFailing() {
        if (failure != null) {
            throw failure;
        }
    }
}
