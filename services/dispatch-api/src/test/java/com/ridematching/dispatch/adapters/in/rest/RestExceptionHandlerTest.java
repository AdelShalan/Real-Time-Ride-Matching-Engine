package com.ridematching.dispatch.adapters.in.rest;

import com.ridematching.dispatch.application.DriverAlreadyAssignedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the API explains itself when a request is rejected.
 *
 * <p>The bug this guards against is not a crash — it is Spring's default error body,
 * {@code {"timestamp","status":400,"error":"Bad Request","path"}}, which names neither the
 * field nor the reason. That response is technically correct and practically useless, and it
 * is what this API returned until these handlers existed.
 *
 * <p>Driven through a standalone MockMvc against a minimal controller rather than the real
 * one: the behaviour under test belongs to the advice, and binding it to the real controller's
 * dependencies would test the wiring instead of the error contract.
 */
class RestExceptionHandlerTest {

    /** Minimal endpoint whose only job is to fail in the ways the advice must handle. */
    @RestController
    static class ProbeController {

        @PostMapping(path = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        RideRequestDto accept(@Valid @RequestBody RideRequestDto body) {
            return body;
        }

        @GetMapping("/probe/conflict")
        String conflict() {
            throw new DriverAlreadyAssignedException("driver already holds an active trip", null);
        }

        @GetMapping("/probe/illegal")
        String illegal() {
            throw new IllegalArgumentException("Unknown vehicleClass: HELICOPTER");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a validation failure names every offending field")
    void validationFailureNamesFields() throws Exception {
        // riderId missing, and latitude out of range.
        String body = """
                {"pickupLat": 999.0, "pickupLng": 31.2357,
                 "dropoffLat": 30.05, "dropoffLng": 31.24}
                """;

        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                // The whole point: the caller learns WHICH fields, not just that something failed.
                .andExpect(jsonPath("$.errors.riderId").exists())
                .andExpect(jsonPath("$.errors.pickupLat").exists());
    }

    @Test
    @DisplayName("malformed JSON explains the expected shape")
    void malformedJsonIsExplained() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request body"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("riderId")));
    }

    @Test
    @DisplayName("the parser's internal message is never echoed to the client")
    void parserDetailIsNotDisclosed() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLat\": \"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                // Jackson names Java classes and field paths in its messages. Returning those
                // hands out a map of the internals and helps no legitimate client.
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("com.ridematching"))));
    }

    @Test
    @DisplayName("a lost driver claim is a 409, not a 500")
    void driverConflictIsNotAServerError() throws Exception {
        // Under contention this is the ADR-0004 guarantee working. A 500 would page someone
        // for a system behaving exactly as designed.
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Driver unavailable"));
    }

    @Test
    @DisplayName("a domain rejection surfaces its own message")
    void domainRejectionKeepsItsMessage() throws Exception {
        mvc.perform(get("/probe/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("HELICOPTER")));
    }
}
