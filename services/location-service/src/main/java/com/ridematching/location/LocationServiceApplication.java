package com.ridematching.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WebSocket GPS ingestion into the Redis geospatial index.
 *
 * <p>Runs on virtual threads (ADR-0006): blocking I/O to Redis, PostgreSQL and Kafka is
 * written as straight-line code, and the JVM unmounts the carrier thread while it waits.
 */
@SpringBootApplication
public class LocationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocationServiceApplication.class, args);
    }
}
