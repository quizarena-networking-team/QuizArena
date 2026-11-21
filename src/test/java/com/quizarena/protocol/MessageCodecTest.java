package com.quizarena.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the shared QuizArena JSON message codec.
 */
class MessageCodecTest {

    /**
     * Verify that a message can be encoded as JSON
     * and decoded without losing its data.
     */
    @Test
    void shouldEncodeAndDecodeMessage() throws Exception {

        ProtocolMessage original = new ProtocolMessage(
                MessageTypes.JOIN_ROOM,
                "req-001",
                "ABC123",
                null,
                null,
                Map.of("playerName", "Amal")
        );

        // Convert message into newline-delimited JSON bytes.
        byte[] encoded = MessageCodec.encodeLine(original);

        // Every TCP message must end with '\n'.
        assertEquals(
                (byte) '\n',
                encoded[encoded.length - 1]
        );

        // Remove the newline before decoding JSON.
        byte[] json = Arrays.copyOf(
                encoded,
                encoded.length - 1
        );

        ProtocolMessage decoded =
                MessageCodec.decodeJson(json);

        // Verify that all message fields are preserved.
        assertEquals(original, decoded);

        assertEquals(
                MessageTypes.JOIN_ROOM,
                decoded.type()
        );

        assertEquals(
                "req-001",
                decoded.requestId()
        );

        assertEquals(
                "ABC123",
                decoded.roomCode()
        );

        assertEquals(
                "Amal",
                decoded.payload().get("playerName")
        );
    }

    /**
     * Ensure that oversized outgoing messages
     * cannot be encoded.
     */
    @Test
    void shouldRejectOversizedOutgoingMessage() {

        ProtocolMessage message = new ProtocolMessage(
                MessageTypes.PING,
                "req-002",
                null,
                null,
                null,
                Map.of(
                        "data",
                        "x".repeat(
                                MessageCodec.MAX_FRAME_BYTES
                        )
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MessageCodec.encodeLine(message)
        );
    }

    /**
     * Ensure that oversized incoming JSON frames
     * are rejected by the decoder.
     */
    @Test
    void shouldRejectOversizedIncomingMessage() {

        byte[] oversized = new byte[
                MessageCodec.MAX_FRAME_BYTES + 1
                ];

        assertThrows(
                IOException.class,
                () -> MessageCodec.decodeJson(oversized)
        );
    }
}
