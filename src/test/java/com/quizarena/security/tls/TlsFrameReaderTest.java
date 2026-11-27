package com.quizarena.security.tls;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TlsFrameReaderTest {
    @Test
    void readsMultipleFramesAndToleratesBlankAndCrLfLines() throws Exception {
        var reader = reader("\n{\"type\":\"PING\"}\r\n{\"type\":\"PONG\"}\n");
        assertEquals("{\"type\":\"PING\"}", text(reader.nextFrame()));
        assertEquals("{\"type\":\"PONG\"}", text(reader.nextFrame()));
        assertNull(reader.nextFrame());
    }

    @Test
    void acceptsExactlyTheConfiguredLimit() throws Exception {
        var reader = new TlsFrameReader(new ByteArrayInputStream("12345\n".getBytes(StandardCharsets.UTF_8)), 5);
        assertEquals("12345", text(reader.nextFrame()));
    }

    @Test
    void rejectsOversizedAndIncompleteFrames() {
        var oversized = new TlsFrameReader(
                new ByteArrayInputStream("123456\n".getBytes(StandardCharsets.UTF_8)), 5);
        assertThrows(IOException.class, oversized::nextFrame);
        assertThrows(IOException.class, reader("incomplete")::nextFrame);
    }

    private static TlsFrameReader reader(String input) {
        return new TlsFrameReader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
