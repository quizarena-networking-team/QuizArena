package com.quizarena.udp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.quizarena.protocol.MessageTypes;

/**
 * Body of one UDP datagram (docs/PROTOCOL.md section 9). UDP messages do not use the
 * TCP envelope; each datagram is one flat JSON object. Fields not used by a type are null
 * and omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UdpMessage(
        String type,
        Integer version,
        String serverName,
        Integer tcpPort,
        Integer tlsPort,
        String roomCode,
        String playerId,
        String message
) {
    public static final int VERSION = 1;

    public static UdpMessage discover() {
        return new UdpMessage(MessageTypes.DISCOVER, VERSION, null, null, null, null, null, null);
    }

    public static UdpMessage discoverResult(String serverName, int tcpPort, int tlsPort) {
        return new UdpMessage(MessageTypes.DISCOVER_RESULT, VERSION, serverName, tcpPort, tlsPort, null, null, null);
    }

    public static UdpMessage heartbeat(String roomCode, String playerId) {
        return new UdpMessage(MessageTypes.HEARTBEAT, VERSION, null, null, null, roomCode, playerId, null);
    }

    public static UdpMessage announcement(String roomCode, String message) {
        return new UdpMessage(MessageTypes.ANNOUNCEMENT, VERSION, null, null, null, roomCode, null, message);
    }

    public boolean is(String expectedType) {
        return expectedType.equals(type);
    }
}
