package com.ridematching.platform.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Metric defaults applied to every service.
 *
 * <p>The project's SLOs are stated as p99s (README), and a p99 cannot be computed from a
 * plain timer — Micrometer publishes only count, sum and max unless histogram buckets are
 * enabled. This turns them on centrally so no service can accidentally ship without the
 * one statistic the load tests are judged on.
 *
 * <p>Percentiles are published as histogram buckets rather than client-side percentiles so
 * Prometheus can aggregate them correctly across instances: a client-side p99 cannot be
 * averaged across replicas without being wrong.
 *
 * <p>Registered as an auto-configuration rather than a {@code @Configuration} because the
 * services do not component-scan {@code com.ridematching.platform} — they scan their own
 * package. Auto-configuration means adding the platform dependency is enough.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class ObservabilityDefaults {

    /**
     * Meters that get histogram buckets. Buckets multiply time-series cardinality, so this
     * is a deliberate allow-list rather than a blanket setting.
     */
    private static final String[] TIMED_METER_PREFIXES = {
            "http.server.requests",
            "matching.duration",
            "redis.geo.query.duration",
            "location.ingest.duration"
    };

    @Bean
    public MeterFilter percentileHistogramFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!isTimed(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        // SLO boundaries from the README. Explicit buckets let Prometheus
                        // answer "what fraction breached the SLO?" directly.
                        .serviceLevelObjectives(
                                (double) Duration.ofMillis(50).toNanos(),
                                (double) Duration.ofMillis(120).toNanos(),
                                (double) Duration.ofMillis(500).toNanos(),
                                (double) Duration.ofSeconds(1).toNanos())
                        .minimumExpectedValue((double) Duration.ofMillis(1).toNanos())
                        .maximumExpectedValue((double) Duration.ofSeconds(10).toNanos())
                        .build()
                        .merge(config);
            }
        };
    }

    private static boolean isTimed(String meterName) {
        for (String prefix : TIMED_METER_PREFIXES) {
            if (meterName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
