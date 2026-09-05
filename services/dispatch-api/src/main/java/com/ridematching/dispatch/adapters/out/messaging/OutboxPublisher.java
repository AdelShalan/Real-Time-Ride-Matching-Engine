package com.ridematching.dispatch.adapters.out.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Drains the outbox to Kafka (ADR-0003).
 *
 * <p>Runs on a short interval rather than reacting to commits. Debezium change-data-capture
 * would remove the polling latency, at the cost of running Debezium; for a system whose
 * matching SLA is 500ms, a 200ms poll is comfortably inside budget and vastly simpler.
 *
 * <p><strong>At-least-once, deliberately.</strong> A row is marked published only after Kafka
 * acknowledges it. If the process dies between the send and the mark, the event is sent again
 * on the next pass — which is exactly why every consumer dedupes on {@code eventId}. The
 * alternative ordering, marking first, would lose events on the same crash, and a lost event
 * is unrecoverable where a duplicate is merely inconvenient.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final int batchSize;

    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(JdbcClient jdbc,
                           KafkaTemplate<String, String> kafka,
                           MeterRegistry meters,
                           Clock clock,
                           int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;

        this.published = Counter.builder("outbox.published")
                .description("Events drained from the outbox to Kafka").register(meters);
        this.failed = Counter.builder("outbox.publish.failed")
                .description("Outbox rows whose send failed and will be retried").register(meters);

        // Backlog depth is the health signal: a growing outbox means Kafka is unreachable or
        // the publisher has stalled, and it is visible long before anyone notices missing
        // notifications.
        meters.gauge("outbox.backlog", this, OutboxPublisher::backlogDepth);
    }

    /**
     * Publishes one batch. Returns how many events were sent.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets several instances drain the same outbox
     * concurrently without any of them blocking on rows another instance already took. Without
     * SKIP LOCKED, a second publisher would serialise behind the first and add nothing.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxRow> batch = jdbc.sql("""
                        SELECT id, event_id, topic, partition_key, payload::text AS payload
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", batchSize)
                .query((rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("partition_key"),
                        rs.getString("payload")))
                .list();

        if (batch.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (OutboxRow row : batch) {
            try {
                // Block on the ack: marking a row published before Kafka confirms it would be
                // exactly the dual-write bug the outbox exists to remove, moved one step later.
                kafka.send(row.topic(), row.partitionKey(), row.payload()).get();
                markPublished(row.id());
                published.increment();
                sent++;
            } catch (InterruptedException e) {
                // Restore the flag only for a genuine interrupt, and stop immediately —
                // swallowing it would leave the thread unable to be shut down.
                Thread.currentThread().interrupt();
                failed.increment();
                break;
            } catch (Exception e) {
                failed.increment();
                // Left unpublished on purpose: the next pass retries it. Ordering within a
                // partition is preserved because we stop at the first failure rather than
                // skipping ahead — publishing row N+1 after N failed would reorder the stream.
                log.warn("Outbox row {} failed to publish, will retry: {}", row.id(), e.toString());
                break;
            }
        }
        return sent;
    }

    private void markPublished(long id) {
        jdbc.sql("UPDATE outbox SET published_at = :now WHERE id = :id")
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
    }

    /** Unpublished rows waiting to be drained. */
    public double backlogDepth() {
        Integer depth = jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Integer.class).single();
        return depth == null ? 0 : depth;
    }

    private record OutboxRow(long id, UUID eventId, String topic, String partitionKey, String payload) {
    }
}
