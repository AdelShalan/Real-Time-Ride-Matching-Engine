package com.ridematching.dispatch.adapters.in.rest;

import com.ridematching.dispatch.application.DriverAlreadyAssignedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into RFC 7807 {@code application/problem+json} responses.
 *
 * <p>Lives in an advice rather than on a controller so every endpoint answers the same way,
 * and so adding a controller does not mean remembering to copy handlers across.
 *
 * <p>The reason this exists: without handlers for validation and parse failures, Spring's
 * default error controller answers instead, and it returns
 * {@code {"timestamp","status":400,"error":"Bad Request","path"}} — no field name, no reason.
 * A client integrating against that has to guess which of six fields was wrong. Terseness is
 * the right default for a framework that cannot know what is safe to disclose; it is the wrong
 * answer for an API that does.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /**
     * Bean validation rejected one or more fields.
     *
     * <p>Every offending field is named with its message, because "400 Bad Request" on a
     * six-field payload tells the caller nothing they can act on. Field names and constraint
     * messages are the client's own input echoed back — no internal detail is disclosed.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            // Keep the first message per field: repeated constraints on one field produce
            // noise rather than clarity.
            errors.putIfAbsent(error.getField(),
                    error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors().forEach(error ->
                errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request body failed validation. See 'errors' for the fields concerned.");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);

        log.debug("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * The body could not be parsed at all — malformed JSON, or a value of the wrong type.
     *
     * <p>The parser's own message is logged but deliberately not returned. Jackson's messages
     * name Java classes and field paths ("Cannot deserialize value of type
     * com.ridematching..."), which hands an attacker a map of the internals for no benefit to
     * a legitimate client, who already knows what they sent.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> onUnreadableBody(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body is not valid JSON, or a field has the wrong type. "
                        + "Expected: riderId (UUID), pickupLat, pickupLng, dropoffLat, dropoffLng "
                        + "(numbers), vehicleClass (STANDARD | XL | PREMIUM, optional).");
        problem.setTitle("Malformed request body");

        log.debug("Unreadable request body: {}",
                e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * The {@code uniq_driver_active_trip} index rejected the write: another worker holds this
     * driver.
     *
     * <p>A 409, not a 500. Under contention this is the ADR-0004 guarantee working as designed,
     * so it is logged at debug and counted rather than raised as an error — a busy dispatch hour
     * would otherwise fill the log with exceptions that represent success.
     */
    @ExceptionHandler(DriverAlreadyAssignedException.class)
    public ResponseEntity<ProblemDetail> onDriverAlreadyAssigned(DriverAlreadyAssignedException e) {
        log.debug("Driver already assigned: {}", e.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "That driver was assigned to another ride.");
        problem.setTitle("Driver unavailable");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** Domain validation rejected an otherwise well-formed request (bad coordinates, unknown enum). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> onInvalidArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }
}
