package com.ridematching.trip;

import com.ridematching.geoindex.DriverClaimStore;
import com.ridematching.geoindex.RedisKeys;
import com.ridematching.geoindex.redis.RedisDriverClaimStore;
import com.ridematching.platform.messaging.ProcessedEvents;
import com.ridematching.platform.outbox.JdbcOutboxWriter;
import com.ridematching.platform.outbox.OutboxPublisher;
import com.ridematching.platform.outbox.OutboxWriter;
import com.ridematching.trip.adapters.in.kafka.RideMatchedConsumer;
import com.ridematching.trip.adapters.out.persistence.JdbcTripStore;
import com.ridematching.trip.application.ClaimReconciler;
import com.ridematching.trip.application.TripLifecycle;
import com.ridematching.trip.application.port.TripStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires the trip service. All Spring knowledge lives here so the use cases and adapters stay
 * constructible by hand in tests (ADR-0001).
 */
@Configuration
@EnableScheduling
public class TripConfiguration implements SchedulingConfigurer {

    private final OutboxPublisher outboxPublisher;
    private final ClaimReconciler claimReconciler;
    private final Duration outboxInterval;
    private final Duration reconcileInterval;

    public TripConfiguration(OutboxPublisher outboxPublisher,
                             ClaimReconciler claimReconciler,
                             @Value("${ridematching.trip.outbox-interval:PT0.2S}")
                             Duration outboxInterval,
                             @Value("${ridematching.trip.reconcile-interval:PT30S}")
                             Duration reconcileInterval) {
        this.outboxPublisher = outboxPublisher;
        this.claimReconciler = claimReconciler;
        this.outboxInterval = outboxInterval;
        this.reconcileInterval = reconcileInterval;
    }

    @Bean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public static RedisKeys redisKeys(@Value("${ridematching.matching.city:default}") String city) {
        // The same property the matching engine reads. Two services pointed at different
        // cities would each see an empty half of the key space, with nothing failing loudly.
        return new RedisKeys(city);
    }

    @Bean
    public static DriverClaimStore driverClaimStore(StringRedisTemplate redis, RedisKeys keys) {
        return new RedisDriverClaimStore(redis, keys);
    }

    @Bean
    public static OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcOutboxWriter(jdbc, objectMapper);
    }

    @Bean
    public static OutboxPublisher outboxPublisher(JdbcClient jdbc,
                                                  KafkaTemplate<String, String> kafka,
                                                  MeterRegistry meters,
                                                  Clock clock) {
        return new OutboxPublisher(jdbc, kafka, meters, clock, 100);
    }

    @Bean
    public static TripStore tripStore(JdbcClient jdbc, OutboxWriter outbox, Clock clock) {
        return new JdbcTripStore(jdbc, outbox, clock);
    }

    @Bean
    public static TripLifecycle tripLifecycle(TripStore trips,
                                              DriverClaimStore claims,
                                              MeterRegistry meters,
                                              Clock clock) {
        return new TripLifecycle(trips, claims, meters, clock);
    }

    @Bean
    public static ClaimReconciler claimReconciler(StringRedisTemplate redis,
                                                  JdbcClient jdbc,
                                                  DriverClaimStore claims,
                                                  RedisKeys keys,
                                                  MeterRegistry meters) {
        return new ClaimReconciler(redis, jdbc, claims, keys, meters, 200);
    }

    @Bean
    public static ProcessedEvents processedEvents(StringRedisTemplate redis) {
        return new ProcessedEvents(redis, "trip-service", Duration.ofHours(24));
    }

    @Bean
    public static RideMatchedConsumer rideMatchedConsumer(TripStore trips,
                                                          DriverClaimStore claims,
                                                          ObjectMapper objectMapper,
                                                          ProcessedEvents processed,
                                                          MeterRegistry meters) {
        return new RideMatchedConsumer(trips, claims, objectMapper, processed, meters);
    }

    /**
     * Two background loops, on very different intervals.
     *
     * <p>The outbox drain is on the request path's critical timeline — an event sitting in the
     * outbox is a notification the rider has not received — so it runs every 200ms. The claim
     * reconciler is a safety net for a case that should not happen at all, and sweeping the
     * Redis keyspace has a cost, so it runs every 30 seconds. Making it faster would not make
     * the system more correct, only busier.
     *
     * <p>Fixed delay rather than fixed rate for both: if a pass outlasts its interval, fixed
     * rate would stack overlapping passes onto whatever is already struggling.
     *
     * <p>Both intervals are properties so a test can push them out of the way. A background
     * loop polling the same tables a test is truncating produces rollback errors that belong
     * to the harness rather than the code, and a green build that prints ERROR teaches people
     * to stop reading the log.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(outboxPublisher::publishBatch, outboxInterval);
        registrar.addFixedDelayTask(claimReconciler::reconcile, reconcileInterval);
    }
}
