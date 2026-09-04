package com.ridematching.domain.driver;

/**
 * Dispatch availability of a driver, mirrored in the Redis {@code driver:{id}:state} hash.
 *
 * <pre>
 *   OFFLINE ──▶ AVAILABLE ──▶ RESERVED ──▶ ON_TRIP ──▶ AVAILABLE
 * </pre>
 *
 * <p>{@link #RESERVED} is the short-lived window between a matching worker winning the Redis
 * claim and the driver either accepting or the offer expiring (ADR-0004). It exists so a
 * second worker's {@code GEOSEARCH} can see the driver is spoken for without consulting
 * PostgreSQL.
 */
public enum DriverStatus {

    /** Not connected. Absent from the geospatial index entirely. */
    OFFLINE,

    /** Connected, idle, and eligible to be returned as a matching candidate. */
    AVAILABLE,

    /** Claimed by a matching worker; an offer is outstanding. */
    RESERVED,

    /** Carrying a rider. */
    ON_TRIP;

    /** Whether a driver in this state may be offered a new ride. */
    public boolean isDispatchable() {
        return this == AVAILABLE;
    }
}
