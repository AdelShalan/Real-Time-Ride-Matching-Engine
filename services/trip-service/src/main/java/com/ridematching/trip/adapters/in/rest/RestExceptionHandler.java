package com.ridematching.trip.adapters.in.rest;

import com.ridematching.domain.trip.IllegalTripTransitionException;
import com.ridematching.trip.application.ConcurrentTripModificationException;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.TripNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns lifecycle failures into RFC 7807 {@code application/problem+json} responses.
 *
 * <p>The distinction this makes is the point of it. Three different things can stop a
 * transition, and a caller has to act differently on each:
 *
 * <ul>
 *   <li><strong>409 from an illegal transition</strong> — the trip is in a state where this
 *       move is not allowed. Retrying will never work; re-read and decide again.</li>
 *   <li><strong>409 from a concurrent modification</strong> — the move might well be legal,
 *       but someone else wrote first. Retrying immediately is the right response, and
 *       {@code Retry-After} says so.</li>
 *   <li><strong>404</strong> — no such trip.</li>
 * </ul>
 *
 * <p>Collapsing the first two into one status without distinguishing them in the body would
 * leave a client either retrying forever on a permanent rejection or giving up on a transient
 * one. The status codes match; the {@code title} is what tells them apart.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /**
     * The state machine refused the move.
     *
     * <p>The message names both states, because "409 Conflict" on its own leaves the caller
     * guessing whether they were too early, too late, or simply wrong.
     */
    @ExceptionHandler(IllegalTripTransitionException.class)
    public ResponseEntity<ProblemDetail> onIllegalTransition(IllegalTripTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Transition not allowed");
        log.debug("Rejected transition: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Someone else wrote first. Retryable, and the header says so.
     *
     * <p>Logged at debug, not error: under contention this is optimistic locking doing its
     * job, and a busy hour should not read as an incident.
     */
    @ExceptionHandler(ConcurrentTripModificationException.class)
    public ResponseEntity<ProblemDetail> onConcurrentModification(ConcurrentTripModificationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The trip changed while this request was being handled. Read it again and retry.");
        problem.setTitle("Concurrent modification");
        log.debug("Optimistic lock lost: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(problem);
    }

    /** The {@code uniq_driver_active_trip} index refused the write (ADR-0004). */
    @ExceptionHandler(DriverAlreadyAssignedException.class)
    public ResponseEntity<ProblemDetail> onDriverAlreadyAssigned(DriverAlreadyAssignedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "That driver is already on another trip.");
        problem.setTitle("Driver unavailable");
        log.debug("Driver already assigned: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ProblemDetail> onNotFound(TripNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Trip not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** Bean validation rejected the body — today, only a missing cancellation reason. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(),
                    error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body failed validation. See 'errors' for the fields concerned.");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** A well-formed request the domain still rejects — a malformed id, an unknown enum. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> onInvalidArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }
}
