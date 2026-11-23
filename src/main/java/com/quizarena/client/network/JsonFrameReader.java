package com.quizarena.client.network;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads complete frames from one TCP stream. Only the socket reader uses this instance. */
final class JsonFrameReader {
    private static final JsonFactory JSON = new JsonFactory();

    private final InputStream input;
    private final byte[] frame = new byte[MessageCodec.MAX_FRAME_BYTES];

    JsonFrameReader(InputStream input) {
        this.input = new BufferedInputStream(input);
    }

    ProtocolMessage read() throws IOException {
        int length = 0;
        int next;
        while ((next = input.read()) != '\n') {
            if (next == -1) {
                throw new EOFException(length == 0 ? "Server closed the connection"
                        : "Server closed the connection in the middle of a JSON frame");
            }
            if (length == frame.length) {
                throw new IOException("Received JSON frame exceeds " + frame.length + " bytes");
            }
            frame[length++] = (byte) next;
        }

        try {
            // The shared codec decodes the envelope. Validate strict UTF-8 and one JSON object first.
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame, 0, length)).toString();
            try (var parser = JSON.createParser(json)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("Expected a JSON object");
                }
                parser.skipChildren();
                if (parser.nextToken() != null) {
                    throw new IOException("Unexpected data after the JSON object");
                }
            }
            return MessageCodec.decodeJson(Arrays.copyOf(frame, length));
        } catch (IOException failure) {
            throw new IOException("Received an invalid UTF-8 JSON frame", failure);
        }
    }
}
