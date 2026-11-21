package com.quizarena.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Arrays;

/**
 * Encodes and decodes shared JSON messages.
 *
 * TCP framing: UTF-8 JSON followed by '\n'.
 */
public final class MessageCodec {

    public static final int MAX_FRAME_BYTES = 16 * 1024;

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private MessageCodec() {
    }

    public static byte[] encodeLine(
            ProtocolMessage message
    ) throws JsonProcessingException {

        byte[] json = MAPPER.writeValueAsBytes(message);

        if (json.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException(
                    "Message exceeds maximum frame size"
            );
        }

        byte[] frame = Arrays.copyOf(
                json, json.length + 1
        );

        frame[frame.length - 1] = (byte) '\n';

        return frame;
    }

    /**
     * Decode one complete JSON frame.
     * The trailing newline must already be removed.
     */
    public static ProtocolMessage decodeJson(
            byte[] json
    ) throws IOException {

        if (json.length > MAX_FRAME_BYTES) {
            throw new IOException(
                    "Received frame exceeds size limit"
            );
        }

        return MAPPER.readValue(
                json, ProtocolMessage.class
        );
    }
}
