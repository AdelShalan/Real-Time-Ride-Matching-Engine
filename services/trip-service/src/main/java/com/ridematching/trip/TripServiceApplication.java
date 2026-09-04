package com.ridematching.trip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Trip state machine, PostgreSQL system of record, transactional outbox.
 *
 * <p>Runs on virtual threads (ADR-0006): blocking I/O to Redis, PostgreSQL and Kafka is
 * written as straight-line code, and the JVM unmounts the carrier thread while it waits.
 */
@SpringBootApplication
public class TripServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripServiceApplication.class, args);
    }
}
