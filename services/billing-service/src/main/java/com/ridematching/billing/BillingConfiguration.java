package com.ridematching.billing;

import com.ridematching.billing.adapters.in.kafka.RideMatchedBilling;
import com.ridematching.platform.messaging.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Wires the consumer. All Spring knowledge lives here (ADR-0001). */
@Configuration
public class BillingConfiguration {

    /**
     * De-duplication markers are kept for 24 hours: comfortably longer than any plausible
     * redelivery window, including a consumer restarting from a badly lagged offset.
     */
    @Bean
    public ProcessedEvents processedEvents(StringRedisTemplate redis) {
        return new ProcessedEvents(redis, "billing-service", Duration.ofHours(24));
    }

    @Bean
    public RideMatchedBilling rideMatchedBilling(ObjectMapper objectMapper, ProcessedEvents processed, MeterRegistry meters) {
        return new RideMatchedBilling(objectMapper, processed, meters);
    }
}
