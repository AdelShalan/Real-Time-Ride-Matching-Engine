package com.ridematching.domain.trip;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * States a trip can occupy, and the only transitions permitted between them.
 *
 * <p>The transition table is declared once, here, rather than scattered across {@code if}
 * statements in service code. Every guard in {@link Trip} consults this, so "what is legal"
 * has a single definition that can be tested exhaustively.
 *
 * <pre>
 *   REQUESTED ──▶ MATCHING ──▶ OFFERED ──▶ ACCEPTED ──▶ IN_PROGRESS ──▶ COMPLETED
 *                    │  ▲         │
 *                    │  └─────────┘  offer declined or expired: try the next candidate
 *                    ▼
 *                UNMATCHED         radius exhausted or the 30s SLA elapsed
 * </pre>
 *
 * <p><strong>IN_PROGRESS cannot be cancelled.</strong> Once a rider is in the vehicle the
 * trip must reach {@link #COMPLETED}; aborting mid-journey is a different business process
 * with its own fare implications, not a cancellation. This mirrors the state diagram in the
 * README and is deliberately restrictive — a real platform would add a distinct
 * {@code ABORTED} state rather than widening cancellation.
 */
public enum TripStatus {

    /** Rider has asked for a trip; nothing has been dispatched yet. */
    REQUESTED,

    /** The matching engine is searching for and claiming candidate drivers. */
    MATCHING,

    /** A driver has been claimed and holds an offer that expires shortly. */
    OFFERED,

    /** The driver accepted and is en route to the pickup point. */
    ACCEPTED,

    /** Rider is in the vehicle. */
    IN_PROGRESS,

    /** Trip finished normally. Terminal. */
    COMPLETED,

    /** Trip was called off before it began. Terminal. */
    CANCELLED,

    /** No driver could be found inside the SLA window. Terminal. */
    UNMATCHED;

    private static final Map<TripStatus, Set<TripStatus>> ALLOWED_TRANSITIONS;

    static {
        // Enum constants are fully constructed before static initialisers run, so
        // referencing them here is safe.
        Map<TripStatus, Set<TripStatus>> allowed = new EnumMap<>(TripStatus.class);
        allowed.put(REQUESTED, EnumSet.of(MATCHING, CANCELLED));
        allowed.put(MATCHING, EnumSet.of(OFFERED, UNMATCHED, CANCELLED));
        allowed.put(OFFERED, EnumSet.of(ACCEPTED, MATCHING, CANCELLED));
        allowed.put(ACCEPTED, EnumSet.of(IN_PROGRESS, CANCELLED));
        allowed.put(IN_PROGRESS, EnumSet.of(COMPLETED));
        allowed.put(COMPLETED, EnumSet.noneOf(TripStatus.class));
        allowed.put(CANCELLED, EnumSet.noneOf(TripStatus.class));
        allowed.put(UNMATCHED, EnumSet.noneOf(TripStatus.class));

        // Each value set must be wrapped individually. Collections.unmodifiableMap only
        // freezes the map itself — the EnumSets inside stay mutable, and this table is
        // static shared state, so a single stray add() would silently rewrite the state
        // machine for the entire JVM. TripStatusTest proves this is now closed.
        Map<TripStatus, Set<TripStatus>> frozen = new EnumMap<>(TripStatus.class);
        allowed.forEach((state, targets) -> frozen.put(state, Collections.unmodifiableSet(targets)));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(frozen);
    }

    /** Whether a trip in this state may move to {@code target}. */
    public boolean canTransitionTo(TripStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** States reachable from this one in a single step. Never null; empty when terminal. */
    public Set<TripStatus> allowedTargets() {
        return ALLOWED_TRANSITIONS.get(this);
    }

    /** A terminal state has no outgoing transitions; the trip is finished for good. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /**
     * Whether a trip in this state holds a driver.
     *
     * <p>This set is exactly the predicate of the {@code uniq_driver_active_trip} partial
     * index in PostgreSQL — the constraint that makes double-assignment physically
     * impossible (ADR-0004). The two must agree: a state that holds a driver in the domain
     * but sits outside the index predicate is a hole in the guarantee.
     *
     * <p>{@link #OFFERED} is included because the trip row is written immediately after the
     * Redis claim succeeds and <em>before</em> the driver accepts. That is what stops a
     * second worker inserting a competing row during the offer window; waiting until
     * acceptance would leave the race open for the offer's whole 8-second TTL.
     *
     * <p>Also used as an invariant check: a trip in one of these states with no assigned
     * driver is a corrupt record, not a valid one.
     */
    public boolean holdsDriver() {
        return this == OFFERED || this == ACCEPTED || this == IN_PROGRESS;
    }
}
