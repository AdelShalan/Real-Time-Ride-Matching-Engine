package com.ridematching.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Downstream consumer: fare calculation on trip completion.
 *
 * <p>Runs on virtual threads (ADR-0006): blocking I/O to Redis, PostgreSQL and Kafka is
 * written as straight-line code, and the JVM unmounts the carrier thread while it waits.
 */
@SpringBootApplication
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
