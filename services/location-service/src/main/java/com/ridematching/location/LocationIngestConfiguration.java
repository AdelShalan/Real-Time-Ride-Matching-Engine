package com.ridematching.location;

import tools.jackson.databind.ObjectMapper;
import com.ridematching.location.adapters.in.ws.DriverHandshakeInterceptor;
import com.ridematching.location.adapters.in.ws.DriverLocationWebSocketHandler;
import com.ridematching.location.adapters.out.redis.RedisDriverLocationIndex;
import com.ridematching.location.application.LocationIngestProperties;
import com.ridematching.location.application.LocationIngestService;
import com.ridematching.location.application.StaleDriverReaper;
import com.ridematching.location.application.port.DriverLocationIndex;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.time.Clock;

/**
 * Wires the ingest path together.
 *
 * <p>All Spring knowledge lives here, which is what keeps {@link LocationIngestService}, the
 * reaper and the adapters constructible by hand in tests (ADR-0001).
 */
@Configuration
@EnableWebSocket
@EnableScheduling
@EnableConfigurationProperties(LocationIngestProperties.class)
public class LocationIngestConfiguration implements WebSocketConfigurer, SchedulingConfigurer {

    private final LocationIngestService ingestService;
    private final StaleDriverReaper reaper;
    private final LocationIngestProperties properties;
    private final ObjectMapper objectMapper;

    public LocationIngestConfiguration(LocationIngestService ingestService,
                                       StaleDriverReaper reaper,
                                       LocationIngestProperties properties,
                                       ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.reaper = reaper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public static DriverLocationIndex driverLocationIndex(StringRedisTemplate redis,
                                                          LocationIngestProperties properties) {
        return new RedisDriverLocationIndex(redis, properties);
    }

    @Bean
    public static LocationIngestService locationIngestService(DriverLocationIndex index,
                                                              LocationIngestProperties properties,
                                                              MeterRegistry meters,
                                                              Clock clock) {
        return new LocationIngestService(index, properties, meters, clock);
    }

    @Bean
    public static StaleDriverReaper staleDriverReaper(DriverLocationIndex index,
                                                      LocationIngestProperties properties,
                                                      Clock clock) {
        return new StaleDriverReaper(index, properties, clock);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new DriverLocationWebSocketHandler(ingestService, objectMapper), "/ws/driver")
                .addInterceptors(new DriverHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    /**
     * Schedules the flusher and the reaper programmatically.
     *
     * <p>Not {@code @Scheduled(fixedDelayString = "...")}: that attribute accepts only a plain
     * millisecond count or an ISO-8601 string, so it cannot read the same {@code 50ms} value
     * that {@code @ConfigurationProperties} binds happily. Registering here means the interval
     * has exactly one definition — {@link LocationIngestProperties} — instead of the same
     * number written twice in two syntaxes and free to drift.
     *
     * <p>Fixed <em>delay</em>, not fixed rate: if a flush ever outlasts its interval, fixed
     * rate would pile overlapping flushes onto a Redis that is already struggling. Fixed delay
     * lets the batch grow instead, which is the cheaper failure and self-correcting.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(ingestService::flush, properties.getFlushInterval());
        registrar.addFixedDelayTask(reaper::reap, properties.getReaperInterval());
    }
}
