package com.ridematching.matching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Consumes ride requests, ranks candidates, claims drivers.
 *
 * <p>Runs on virtual threads (ADR-0006): blocking I/O to Redis, PostgreSQL and Kafka is
 * written as straight-line code, and the JVM unmounts the carrier thread while it waits.
 */
@SpringBootApplication
public class MatchingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchingEngineApplication.class, args);
    }
}
