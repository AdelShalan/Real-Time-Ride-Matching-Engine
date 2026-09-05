package com.ridematching.dispatch;

import com.ridematching.dispatch.adapters.out.messaging.JdbcOutboxWriter;
import com.ridematching.dispatch.adapters.out.messaging.OutboxPublisher;
import com.ridematching.dispatch.adapters.out.persistence.JdbcIdempotencyStore;
import com.ridematching.dispatch.adapters.out.persistence.JdbcTripRepository;
import com.ridematching.dispatch.application.port.OutboxWriter;
import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.dispatch.application.port.TripRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Wires the dispatch API. All Spring knowledge lives here so the use case and adapters stay
 * constructible by hand in tests (ADR-0001).
 */
@Configuration
@EnableScheduling
public class DispatchConfiguration implements SchedulingConfigurer {

    private final OutboxPublisher outboxPublisher;

    public DispatchConfiguration(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    @Bean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public static OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcOutboxWriter(jdbc, objectMapper);
    }

    @Bean
    public static TripRepository tripRepository(JdbcClient jdbc, OutboxWriter outbox) {
        return new JdbcTripRepository(jdbc, outbox);
    }

    @Bean
    public static OutboxPublisher outboxPublisher(JdbcClient jdbc,
                                                  KafkaTemplate<String, String> kafka,
                                                  MeterRegistry meters,
                                                  Clock clock) {
        return new OutboxPublisher(jdbc, kafka, meters, clock, 100);
    }

    @Bean
    public static IdempotencyStore idempotencyStore(JdbcClient jdbc, Clock clock) {
        return new JdbcIdempotencyStore(jdbc, clock);
    }

    @Bean
    public static RequestRideUseCase requestRideUseCase(TripRepository trips,
                                                        IdempotencyStore idempotency,
                                                        OutboxWriter outbox,
                                                        MeterRegistry meters,
                                                        Clock clock) {
        return new RequestRideUseCase(trips, idempotency, outbox, meters, clock);
    }

    /**
     * Drains the outbox on a short fixed delay.
     *
     * <p>Fixed delay rather than fixed rate: if Kafka slows and a batch outlasts the interval,
     * fixed rate would stack overlapping drains onto an already-struggling broker. Fixed delay
     * lets the backlog grow instead, which the outbox.backlog gauge makes visible.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(outboxPublisher::publishBatch, java.time.Duration.ofMillis(200));
    }
}
