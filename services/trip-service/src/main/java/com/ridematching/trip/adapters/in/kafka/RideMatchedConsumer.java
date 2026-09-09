package com.ridematching.trip.adapters.in.kafka;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.trip.RideId;
import com.ridematching.domain.trip.Trip;
import com.ridematching.events.RideMatched;
import com.ridematching.events.RideUnmatched;
import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.platform.messaging.ProcessedEvents;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.port.TripStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Makes a match durable.
 *
 * <p>This is the write that the whole concurrency design is built around. The matching engine
 * claims a driver in Redis and announces it; until this consumer runs, that claim is the only
 * record of the assignment and it is one that expires. Here the assignment enters PostgreSQL,
 * where {@code uniq_driver_active_trip} either accepts it or rejects it — and the rejection
 * is the guarantee working, not a bug.
 *
 * <p>The two layers can genuinely disagree. Redis grants a claim to whoever asks first; the
 * index rejects a driver already recorded on another trip. A Redis flush, an expired lease, or
 * a paused worker are all cases where the fast path says yes and the durable one says no. When
 * that happens the correct action is to give the driver straight back, which is what the
 * failure branch below does — otherwise a driver would be reserved in Redis for a trip that
 * never got them.
 */
public class RideMatchedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideMatchedConsumer.class);

    private final TripStore trips;
    private final DriverClaimStore claims;
    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;

    private final Counter recorded;
    private final Counter rejected;
    private final Counter unmatched;

    public RideMatchedConsumer(TripStore trips,
                               DriverClaimStore claims,
                               ObjectMapper objectMapper,
                               ProcessedEvents processed,
                               MeterRegistry meters) {
        this.trips = trips;
        this.claims = claims;
        this.objectMapper = objectMapper;
        this.processed = processed;

        this.recorded = Counter.builder("trip.assignment.recorded")
                .description("Matches made durable in PostgreSQL").register(meters);
        // The one to watch in a load test. Non-zero means Redis and PostgreSQL disagreed and
        // the index arbitrated — exactly the event S3 is designed to provoke.
        this.rejected = Counter.builder("trip.assignment.rejected")
                .description("Matches the unique index refused").register(meters);
        this.unmatched = Counter.builder("trip.unmatched")
                .description("Requests that found no driver").register(meters);
    }

    @KafkaListener(topics = "${ridematching.topics.ride-matched:ride.matched.v1}",
            groupId = "trip-service")
    public void onRideMatched(String payload) {
        RideMatched event = objectMapper.readValue(payload, RideMatched.class);

        if (!processed.markIfFirst(event.eventId().toString())) {
            log.debug("Skipping duplicate match event {}", event.eventId());
            return;
        }

        try {
            record(event);
        } catch (DriverAlreadyAssignedException e) {
            // Not rethrown. A redelivery would fail identically, so retrying forever would
            // stall the partition on an event whose outcome is already settled: this trip does
            // not get this driver. The marker stays set for the same reason.
            rejected.increment();
            releaseLostClaim(event);
            log.info("Match for ride {} refused by the unique index; driver {} released",
                    event.rideId(), event.driverId());
        } catch (RuntimeException e) {
            // Anything else is transient. Forget the marker so the redelivery is a real retry
            // rather than a silent drop.
            processed.forget(event.eventId().toString());
            throw e;
        }
    }

    private void record(RideMatched event) {
        RideId rideId = new RideId(event.rideId());
        Optional<TripStore.Versioned> loaded = trips.load(rideId);
        if (loaded.isEmpty()) {
            // The match arrived before its trip exists, which the outbox makes impossible: the
            // request event is only published after the trip row commits. Worth saying out
            // loud rather than silently ignoring, because if it ever happens the outbox
            // ordering assumption has broken.
            log.warn("Match for unknown ride {}; ignoring", event.rideId());
            return;
        }

        Trip trip = loaded.get().trip();
        // The matching engine consumed the request, so the trip is still REQUESTED here —
        // MATCHING is the state it was in while the search ran, and that search happened in
        // another service. Recording it keeps the audit trail honest about what took place.
        trip.beginMatching();
        trip.offerTo(new DriverId(event.driverId()), event.fenceToken(), event.offerToken());

        trips.save(trip, loaded.get().version(), null);
        recorded.increment();
    }

    /**
     * Hands back a driver the index would not let this trip have.
     *
     * <p>Without this the driver stays RESERVED in Redis until the claim TTL lapses, unable to
     * be offered to anyone — a driver taken out of the pool by a match that did not happen.
     */
    private void releaseLostClaim(RideMatched event) {
        try {
            claims.release(new DriverId(event.driverId()), event.offerToken());
        } catch (RuntimeException e) {
            log.warn("Could not release claim for driver {}: {}", event.driverId(), e.toString());
        }
    }

    @KafkaListener(topics = "${ridematching.topics.ride-unmatched:ride.unmatched.v1}",
            groupId = "trip-service")
    public void onRideUnmatched(String payload) {
        RideUnmatched event = objectMapper.readValue(payload, RideUnmatched.class);

        if (!processed.markIfFirst(event.eventId().toString())) {
            return;
        }

        try {
            RideId rideId = new RideId(event.rideId());
            trips.load(rideId).ifPresent(loaded -> {
                Trip trip = loaded.trip();
                trip.beginMatching();
                trip.markUnmatched(event.reason());
                trips.save(trip, loaded.version(), null);
                unmatched.increment();
            });
        } catch (RuntimeException e) {
            processed.forget(event.eventId().toString());
            throw e;
        }
    }
}
