package com.ridematching.matching.adapters.in.kafka;

import com.ridematching.domain.geo.Coordinates;
import com.ridematching.events.RideMatched;
import com.ridematching.events.RideUnmatched;
import com.ridematching.events.RideRequested;
import com.ridematching.matching.application.DriverMatcher;
import com.ridematching.platform.messaging.ProcessedEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes ride requests and runs the matching loop.
 *
 * <p>This is where the request path and the dispatch path finally separate: the API returned
 * {@code 202} long ago, and everything here happens on its own schedule against its own
 * consumer group.
 */
public class RideRequestedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideRequestedConsumer.class);

    private final DriverMatcher matcher;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;
    private final Clock clock;

    public RideRequestedConsumer(DriverMatcher matcher,
                                 KafkaTemplate<String, String> kafka,
                                 ObjectMapper objectMapper,
                                 ProcessedEvents processed,
                                 Clock clock) {
        this.matcher = matcher;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.clock = clock;
    }

    @KafkaListener(topics = "${ridematching.topics.ride-requested:ride.requested.v1}",
            groupId = "matching-engine")
    public void onRideRequested(String payload) {
        RideRequested request = objectMapper.readValue(payload, RideRequested.class);

        // Delivery is at-least-once, so a redelivered request must not claim a second driver
        // for a ride that already has one.
        if (!processed.markIfFirst(request.eventId().toString())) {
            log.debug("Skipping duplicate ride request event {}", request.eventId());
            return;
        }

        try {
            handle(request);
        } catch (RuntimeException e) {
            // Un-mark so the redelivery actually retries. Leaving the marker in place would
            // turn one transient failure into a permanently dropped ride.
            processed.forget(request.eventId().toString());
            throw e;
        }
    }

    private void handle(RideRequested request) {
        Coordinates pickup = new Coordinates(request.pickupLat(), request.pickupLng());
        // The offer token is derived from the ride, not random, so a retry of the same ride
        // produces the same token and can recognise its own earlier claim.
        String offerToken = "offer-" + request.rideId();

        Optional<DriverMatcher.MatchedDriver> match = matcher.match(pickup, offerToken);

        if (match.isPresent()) {
            DriverMatcher.MatchedDriver driver = match.get();
            publish(new RideMatched(
                    UUID.randomUUID(),
                    request.rideId(),
                    driver.driverId().value(),
                    driver.fenceToken(),
                    driver.offerToken(),
                    driver.distanceMeters(),
                    clock.instant()));
        } else {
            // Published rather than dropped: a rider whose request quietly evaporates is worse
            // than one told no driver was available.
            publish(new RideUnmatched(
                    UUID.randomUUID(),
                    request.rideId(),
                    "no claimable driver within the configured radii",
                    clock.instant()));
        }
    }

    private void publish(com.ridematching.events.DomainEvent event) {
        kafka.send(event.topic(), event.partitionKey(), objectMapper.writeValueAsString(event));
    }
}
