package com.ridematching.trip.application;

import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.domain.trip.TripSnapshot;
import com.ridematching.events.DomainEvent;
import com.ridematching.trip.application.port.TripStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link TripStore} that enforces the version check.
 *
 * <p>Stores snapshots rather than aggregates, so every {@code load} rehydrates exactly as the
 * JDBC adapter does. A fake that handed back the same mutable object would hide the
 * possibility that a field is lost on the round trip — which is the specific bug this shape
 * of test is worth having.
 */
class FakeTripStore implements TripStore {

    private final Map<RideId, TripSnapshot> rows = new HashMap<>();
    private final Map<RideId, Integer> versions = new HashMap<>();
    private final List<DomainEvent> outbox = new ArrayList<>();
    private final Clock clock;
    private RideId bumpAfterLoad;

    FakeTripStore(Clock clock) {
        this.clock = clock;
    }

    void put(Trip trip) {
        rows.put(trip.id(), trip.snapshot());
        versions.put(trip.id(), 0);
    }

    /**
     * Makes another writer commit in the gap between the next load and its save.
     *
     * <p>Bumping the version up front would not reproduce anything: the caller would simply
     * read the new version and write against it successfully. The lost update this guards
     * against only exists in the window after a read, so that is where the interference has
     * to happen.
     */
    void bumpVersionAfterNextLoad(RideId rideId) {
        bumpAfterLoad = rideId;
    }

    List<DomainEvent> published() {
        return List.copyOf(outbox);
    }

    @Override
    public Optional<Versioned> load(RideId rideId) {
        Optional<Versioned> loaded = Optional.ofNullable(rows.get(rideId))
                .map(snapshot -> new Versioned(Trip.rehydrate(snapshot, clock), versions.get(rideId)));

        if (rideId.equals(bumpAfterLoad)) {
            bumpAfterLoad = null;
            versions.computeIfPresent(rideId, (id, version) -> version + 1);
        }
        return loaded;
    }

    @Override
    public void save(Trip trip, int expectedVersion, DomainEvent event) {
        int current = versions.getOrDefault(trip.id(), -1);
        if (current != expectedVersion) {
            throw new ConcurrentTripModificationException(trip.id(), expectedVersion);
        }
        rows.put(trip.id(), trip.snapshot());
        versions.put(trip.id(), current + 1);
        if (event != null) {
            outbox.add(event);
        }
    }
}
