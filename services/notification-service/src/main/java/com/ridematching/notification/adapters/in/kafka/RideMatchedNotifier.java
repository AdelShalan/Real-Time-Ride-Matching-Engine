package com.ridematching.notification.adapters.in.kafka;

import com.ridematching.events.RideMatched;
import com.ridematching.platform.messaging.ProcessedEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Tells the rider a driver is on the way.
 *
 * <p>A separate consumer group from every other service, which is what makes the fan-out
 * independent: this service can be stopped, redeployed or crash without affecting matching
 * latency or the other consumers. The load test proves that by killing it mid-run.
 *
 * <p>Simulated rather than real — there is no push gateway or payment processor here, and
 * pretending otherwise would be dishonest. What is real is the decoupling, the consumer group
 * semantics, and the de-duplication.
 */
public class RideMatchedNotifier {

    private static final Logger log = LoggerFactory.getLogger(RideMatchedNotifier.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEvents processed;
    private final Counter handled;
    private final Counter duplicates;

    public RideMatchedNotifier(ObjectMapper objectMapper, ProcessedEvents processed, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.handled = Counter.builder("notifications.handled")
                .description("RideMatched events acted on").register(meters);
        this.duplicates = Counter.builder("notifications.duplicates")
                .description("Redeliveries suppressed by de-duplication").register(meters);
    }

    @KafkaListener(topics = "${ridematching.topics.ride-matched:ride.matched.v1}",
            groupId = "notification-service")
    public void onRideMatched(String payload) {
        RideMatched event = objectMapper.readValue(payload, RideMatched.class);

        if (!processed.markIfFirst(event.eventId().toString())) {
            duplicates.increment();
            return;
        }

        try {
            log.info("Notifying rider: ride {} matched to driver {} ({}m away)",
                    event.rideId(), event.driverId(), Math.round(event.distanceMeters()));
            handled.increment();
        } catch (RuntimeException e) {
            // Forget the marker so the redelivery is a real retry rather than a silent drop.
            processed.forget(event.eventId().toString());
            throw e;
        }
    }
}
