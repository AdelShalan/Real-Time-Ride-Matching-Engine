package com.ridematching.dispatch.application;

/**
 * Raised when {@code uniq_driver_active_trip} rejects a write — the ADR-0004 guarantee firing.
 *
 * <p>This is <strong>normal control flow</strong>, not an error: it means another worker won
 * the race for the same driver, and the caller should move to its next candidate. It is
 * counted rather than logged at ERROR, so a busy dispatch hour does not drown the log in
 * exceptions that represent the system working correctly.
 */
public class DriverAlreadyAssignedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DriverAlreadyAssignedException(String message, Throwable cause) {
        super(message, cause);
    }
}
