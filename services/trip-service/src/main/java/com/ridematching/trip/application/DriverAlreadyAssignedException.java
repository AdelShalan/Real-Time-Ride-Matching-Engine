package com.ridematching.trip.application;

/**
 * Raised when {@code uniq_driver_active_trip} rejects a write — the ADR-0004 guarantee firing.
 *
 * <p>This is <strong>normal control flow</strong>, not an error: another trip already holds
 * this driver, and the correct response is to give up on that driver rather than retry. It is
 * counted rather than logged at ERROR, so contention does not drown the log in exceptions that
 * represent the system working correctly.
 *
 * <p>Deliberately not shared with dispatch-api's identically named type. The two services
 * publish separate APIs and the exception is part of each one's internal vocabulary; hoisting
 * it into a shared module to save fifteen lines would couple them for no benefit.
 */
public class DriverAlreadyAssignedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DriverAlreadyAssignedException(String message, Throwable cause) {
        super(message, cause);
    }
}
