package com.ridematching.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the context starts and the shared platform auto-configuration is picked up.
 * Catches the common wiring mistakes (missing bean, bad property, clashing config) before
 * anything more expensive runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("application context loads")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("platform observability defaults are auto-configured")
    void observabilityDefaultsApplied() {
        assertThat(context.containsBean("percentileHistogramFilter")).isTrue();
    }
}
