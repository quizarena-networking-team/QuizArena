package com.quizarena.udp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * Encodes and decodes single UDP datagrams: UTF-8 JSON, no newline framing,
 * at most {@value #MAX_DATAGRAM_BYTES} bytes (docs/PROTOCOL.md section 9).
 */
public final class DatagramCodec {

    public static final int MAX_DATAGRAM_BYTES = 1200;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DatagramCodec() {
    }

    /** @throws IllegalArgumentException if the encoded message exceeds the datagram limit */
    public static byte[] encode(UdpMessage message) {
        byte[] json;
        try {
            json = MAPPER.writeValueAsBytes(message);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("UDP message could not be encoded", failure);
        }
        if (json.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("UDP message exceeds " + MAX_DATAGRAM_BYTES + " bytes");
        }
        return json;
    }

    /**
     * Decodes one received datagram. Returns empty for anything that is not a valid
     * version-1 message: oversized, malformed JSON, missing type or a different version.
     * UDP input is untrusted, so callers simply drop empty results.
     */
    public static Optional<UdpMessage> decode(byte[] data, int offset, int length) {
        if (length <= 0 || length > MAX_DATAGRAM_BYTES) {
            return Optional.empty();
        }
        try {
            UdpMessage message = MAPPER.readValue(data, offset, length, UdpMessage.class);
            if (message == null || message.type() == null || message.type().isBlank()
                    || message.version() == null || message.version() != UdpMessage.VERSION) {
                return Optional.empty();
            }
            return Optional.of(message);
        } catch (IOException malformed) {
            return Optional.empty();
        }
    }
}
