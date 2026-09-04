package com.ridematching.dispatch.adapters.out.persistence;

import com.ridematching.dispatch.application.port.IdempotencyRecord;
import com.ridematching.dispatch.application.port.IdempotencyStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed idempotency keys (ADR-0005).
 *
 * <p>Plain SQL through {@link JdbcClient} rather than JPA. The whole mechanism turns on the
 * exact semantics of one statement — {@code INSERT ... ON CONFLICT DO NOTHING} — and an ORM
 * would obscure the thing that makes it correct.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    /** How long a completed response stays replayable. */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcIdempotencyStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean tryClaim(String key, String requestHash) {
        // ON CONFLICT DO NOTHING makes this a single atomic decision. Two concurrent retries
        // both execute it; exactly one reports 1 row affected. There is deliberately no
        // SELECT first — that would open the window this exists to close.
        //
        // The WHERE clause on the DO NOTHING target also lets a claim be re-taken once the
        // previous one has expired, so a key is not poisoned forever.
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (key, request_hash, state, created_at, expires_at)
                        VALUES (:key, :hash, 'IN_PROGRESS', :now, :expires)
                        ON CONFLICT (key) DO NOTHING
                        """)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("expires", java.sql.Timestamp.from(clock.instant().plus(RETENTION)))
                .update();

        return inserted == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT key, request_hash, state, ride_id, response_status, response_body
                        FROM idempotency_keys
                        WHERE key = :key AND expires_at > :now
                        """)
                .param("key", key)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("key"),
                        rs.getString("request_hash"),
                        "IN_PROGRESS".equals(rs.getString("state")),
                        rs.getObject("ride_id", UUID.class),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body")))
                .optional();
    }

    @Override
    public void complete(String key, UUID rideId, int responseStatus, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET state = 'COMPLETED',
                            ride_id = :rideId,
                            response_status = :status,
                            response_body = CAST(:body AS JSONB)
                        WHERE key = :key
                        """)
                .param("key", key)
                .param("rideId", rideId)
                .param("status", responseStatus)
                .param("body", responseBody)
                .update();
    }

    @Override
    public void release(String key) {
        // Only release a claim that is still IN_PROGRESS. A completed record must never be
        // deleted by a late failure handler, or a valid stored response would vanish.
        jdbc.sql("DELETE FROM idempotency_keys WHERE key = :key AND state = 'IN_PROGRESS'")
                .param("key", key)
                .update();
    }

    @Override
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at <= :now")
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .update();
    }
}
