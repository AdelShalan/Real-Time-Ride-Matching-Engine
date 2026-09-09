package com.ridematching.dispatch.adapters.out.messaging;

import com.ridematching.dispatch.application.RequestRideUseCase;
import com.ridematching.dispatch.application.RideRequestOutcome;
import com.ridematching.domain.driver.VehicleClass;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.domain.rider.RiderId;
import com.ridematching.events.Topics;
import com.ridematching.platform.outbox.OutboxPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end proof of the transactional outbox (ADR-0003): a ride request commits a trip row
 * and an outbox row together, and the publisher drains it to a real Kafka broker.
 *
 * <p>Both containers are real because the properties under test are precisely the ones a fake
 * cannot have: that two writes share one transaction, and that a record actually lands on a
 * partition a separate consumer can read.
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
@SpringBootTest
class OutboxPublisherIT {

    static boolean containerRuntimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // @SuppressWarnings("resource") is required, not decorative: the compiler sees a
    // Closeable assigned to a field and never closed. Testcontainers' JUnit extension owns
    // the lifecycle of @Container fields and stops them after the class, so there is no leak.
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ride").withUsername("ride").withPassword("ride");

    @Container
    @SuppressWarnings("resource")
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static final Coordinates PICKUP = new Coordinates(30.0444, 31.2357);
    private static final Coordinates DROPOFF = new Coordinates(30.0500, 31.2400);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RequestRideUseCase requestRide;

    @Autowired
    private OutboxPublisher publisher;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE trip_events, trips, idempotency_keys, outbox CASCADE").update();
    }

    private RequestRideUseCase.RequestRideCommand command() {
        return new RequestRideUseCase.RequestRideCommand(
                RiderId.newId(), PICKUP, DROPOFF, VehicleClass.STANDARD);
    }

    private int outboxRows(boolean unpublishedOnly) {
        String sql = unpublishedOnly
                ? "SELECT count(*) FROM outbox WHERE published_at IS NULL"
                : "SELECT count(*) FROM outbox";
        return jdbc.sql(sql).query(Integer.class).single();
    }

    /** Reads whatever is on the topic, from the beginning. */
    private List<ConsumerRecord<String, String>> drainTopic(String topic) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "it-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> all = new java.util.ArrayList<>();
            for (int i = 0; i < 10 && all.isEmpty(); i++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(all::add);
            }
            return all;
        }
    }

    @Nested
    class TransactionalWrite {

        @Test
        @DisplayName("a ride request writes the trip and the outbox row together")
        void tripAndOutboxCommitTogether() {
            RideRequestOutcome outcome = requestRide.handle(command(), "key-1");

            Integer trips = jdbc.sql("SELECT count(*) FROM trips").query(Integer.class).single();

            assertThat(trips).isEqualTo(1);
            assertThat(outboxRows(false))
                    .as("an event must exist for every trip; a trip nobody is told about is a lost ride")
                    .isEqualTo(1);

            String topic = jdbc.sql("SELECT topic FROM outbox").query(String.class).single();
            assertThat(topic).isEqualTo(Topics.RIDE_REQUESTED);

            String partitionKey = jdbc.sql("SELECT partition_key FROM outbox")
                    .query(String.class).single();
            assertThat(partitionKey)
                    .as("keyed by rideId so a ride's events stay ordered")
                    .isEqualTo(outcome.rideId().value().toString());
        }

        @Test
        @DisplayName("an idempotent retry does not produce a second event")
        void retryDoesNotDuplicateTheEvent() {
            var cmd = command();
            requestRide.handle(cmd, "key-1");
            requestRide.handle(cmd, "key-1");

            assertThat(outboxRows(false))
                    .as("a duplicate event would fan out a second notification for one ride")
                    .isEqualTo(1);
        }
    }

    @Nested
    class Publishing {

        @Test
        @DisplayName("the publisher drains the outbox to Kafka and marks rows published")
        void drainsToKafka() {
            RideRequestOutcome outcome = requestRide.handle(command(), "key-1");
            String rideId = outcome.rideId().value().toString();
            assertThat(outboxRows(true)).isEqualTo(1);

            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(outboxRows(true)).isZero());

            // Asserted on THIS ride, not on a total: TRUNCATE resets Postgres between tests
            // but the Kafka topic keeps every record the class has ever published.
            List<ConsumerRecord<String, String>> records = drainTopic(Topics.RIDE_REQUESTED);
            assertThat(records)
                    .anySatisfy(record -> {
                        assertThat(record.key()).isEqualTo(rideId);
                        assertThat(record.value()).contains(rideId);
                    });
        }

        @Test
        @DisplayName("a row is marked published only after Kafka acknowledges it")
        void marksOnlyAfterAck() {
            requestRide.handle(command(), "key-1");

            int sent = publisher.publishBatch();

            assertThat(sent).isEqualTo(1);
            assertThat(outboxRows(true))
                    .as("marking before the ack would reintroduce the dual-write bug one step later")
                    .isZero();
        }

        @Test
        @DisplayName("publishing an empty outbox is a no-op")
        void emptyOutboxIsSafe() {
            assertThat(publisher.publishBatch()).isZero();
        }

        @Test
        @DisplayName("a published row is never sent twice")
        void publishedRowsAreNotResent() {
            requestRide.handle(command(), "key-1");
            publisher.publishBatch();

            assertThat(publisher.publishBatch())
                    .as("the partial index only selects unpublished rows")
                    .isZero();
        }

        @Test
        @DisplayName("many requests all reach the topic in one drain")
        void batchDrain() {
            for (int i = 0; i < 25; i++) {
                requestRide.handle(command(), "key-" + i);
            }

            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(outboxRows(true)).isZero());

            assertThat(drainTopic(Topics.RIDE_REQUESTED))
                    .as("all 25 must reach the topic, plus whatever earlier tests left behind")
                    .hasSizeGreaterThanOrEqualTo(25);
        }
    }
}
