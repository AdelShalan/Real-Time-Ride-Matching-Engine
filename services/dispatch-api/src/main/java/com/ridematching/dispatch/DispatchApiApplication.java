package com.ridematching.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ride request intake: REST API, idempotency, hand-off to Kafka.
 *
 * <p>Runs on virtual threads (ADR-0006): blocking I/O to Redis, PostgreSQL and Kafka is
 * written as straight-line code, and the JVM unmounts the carrier thread while it waits.
 */
@SpringBootApplication
public class DispatchApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchApiApplication.class, args);
    }
}
