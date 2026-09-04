package com.ridematching.dispatch;

import com.ridematching.dispatch.adapters.out.persistence.JdbcIdempotencyStore;
import com.ridematching.dispatch.adapters.out.persistence.JdbcTripRepository;
import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import com.ridematching.dispatch.application.port.TripRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;

/**
 * Wires the dispatch API. All Spring knowledge lives here so the use case and adapters stay
 * constructible by hand in tests (ADR-0001).
 */
@Configuration
public class DispatchConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TripRepository tripRepository(JdbcClient jdbc) {
        return new JdbcTripRepository(jdbc);
    }

    @Bean
    public IdempotencyStore idempotencyStore(JdbcClient jdbc, Clock clock) {
        return new JdbcIdempotencyStore(jdbc, clock);
    }

    @Bean
    public RequestRideUseCase requestRideUseCase(TripRepository trips,
                                                 IdempotencyStore idempotency,
                                                 MeterRegistry meters,
                                                 Clock clock) {
        return new RequestRideUseCase(trips, idempotency, meters, clock);
    }
}
