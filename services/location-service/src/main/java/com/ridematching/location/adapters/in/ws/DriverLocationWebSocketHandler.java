package com.ridematching.location.adapters.in.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.location.application.LocationIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * Terminates driver WebSocket connections and feeds frames into the ingest path.
 *
 * <p>One virtual thread per connection (ADR-0006), which is what makes 10,000 concurrent
 * sockets affordable: each is roughly 1 KB of heap rather than a 1 MB OS thread stack.
 *
 * <p>The driver id is taken from the session, established at handshake, and never from the
 * frame body. Trusting a self-declared id in the payload would let any connected client
 * overwrite any driver's position — including moving a competitor across town.
 */
public class DriverLocationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationWebSocketHandler.class);
    private static final String DRIVER_ID_ATTRIBUTE = "driverId";

    private final LocationIngestService ingest;
    private final ObjectMapper objectMapper;

    public DriverLocationWebSocketHandler(LocationIngestService ingest, ObjectMapper objectMapper) {
        this.ingest = ingest;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        DriverId driverId = driverIdOf(session);
        if (driverId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("missing driver id"));
            return;
        }
        ingest.onConnect(driverId);
        log.debug("Driver {} connected on session {}", driverId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        DriverId driverId = driverIdOf(session);
        if (driverId == null) {
            return;
        }

        LocationFrame frame;
        try {
            frame = objectMapper.readValue(message.getPayload(), LocationFrame.class);
        } catch (Exception e) {
            // A malformed frame is a client bug, not a server error. Log at debug and keep
            // the connection: one bad frame should not disconnect a moving vehicle.
            log.debug("Malformed frame from driver {}: {}", driverId, e.getMessage());
            return;
        }

        try {
            ingest.ingest(new DriverLocation(
                    driverId,
                    new Coordinates(frame.lat(), frame.lng()),
                    frame.heading(),
                    frame.speedKph(),
                    Instant.ofEpochMilli(frame.ts())));
        } catch (IllegalArgumentException e) {
            // Out-of-range coordinates, heading or speed — rejected by the domain records.
            log.debug("Invalid frame from driver {}: {}", driverId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        DriverId driverId = driverIdOf(session);
        if (driverId != null) {
            ingest.onDisconnect(driverId);
            log.debug("Driver {} disconnected: {}", driverId, status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on session {}: {}", session.getId(), exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private DriverId driverIdOf(WebSocketSession session) {
        Object attribute = session.getAttributes().get(DRIVER_ID_ATTRIBUTE);
        if (attribute instanceof DriverId id) {
            return id;
        }
        if (attribute instanceof String raw) {
            try {
                return new DriverId(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("Failed to close session {}: {}", session.getId(), e.getMessage());
        }
    }
}
