package com.ridematching.platform.outbox;

import com.ridematching.events.DomainEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Writes outbox rows through the same {@link JdbcClient} the trip insert uses, so both join
 * the caller's transaction automatically.
 */
public class JdbcOutboxWriter implements OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(DomainEvent event) {
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_id, event_id, topic, partition_key, payload, created_at)
                        VALUES (:aggregateId, :eventId, :topic, :partitionKey, CAST(:payload AS JSONB), :createdAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("aggregateId", UUID.fromString(event.partitionKey()))
                .param("eventId", event.eventId())
                .param("topic", event.topic())
                .param("partitionKey", event.partitionKey())
                .param("payload", objectMapper.writeValueAsString(event))
                .param("createdAt", Timestamp.from(event.occurredAt()))
                .update();
    }
}
