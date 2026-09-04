package com.ridematching.location.adapters.in.ws;

import com.ridematching.domain.driver.DriverId;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * Establishes the driver identity once, at handshake, and pins it to the session.
 *
 * <p>This is the single point where a connection is bound to a driver. Every frame that
 * arrives later inherits that identity, which is why the frame body carries no id.
 *
 * <p><strong>Authentication is stubbed.</strong> The id is read from a query parameter, which
 * is fine for a simulation harness and unacceptable in production — a real deployment would
 * validate a signed token here and derive the id from its claims. Called out rather than
 * quietly left looking finished.
 */
public class DriverHandshakeInterceptor implements HandshakeInterceptor {

    private static final String DRIVER_ID_ATTRIBUTE = "driverId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return false;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "driverId".equals(pair.substring(0, eq))) {
                try {
                    attributes.put(DRIVER_ID_ATTRIBUTE,
                            new DriverId(UUID.fromString(pair.substring(eq + 1))));
                    return true;
                } catch (IllegalArgumentException e) {
                    return false; // rejects the handshake with 403
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // nothing to do
    }
}
