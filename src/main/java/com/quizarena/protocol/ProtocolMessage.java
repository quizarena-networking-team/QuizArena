package com.quizarena.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;

/**
 * Shared envelope for TCP and TLS JSON messages.
 *
 * Requests: type + requestId + payload
 * Responses: type + requestId + status + payload
 * Events: type + payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtocolMessage(
        String type,
        String requestId,
        String roomCode,
        String status,
        String errorCode,
        Map<String, Object> payload
) {
    public ProtocolMessage {
        Objects.requireNonNull(type, "type is required");

        if (type.isBlank()) {
            throw new IllegalArgumentException(
                    "Message type cannot be empty"
            );
        }

        if (payload == null) {
            payload = Map.of();
        }
    }
}
