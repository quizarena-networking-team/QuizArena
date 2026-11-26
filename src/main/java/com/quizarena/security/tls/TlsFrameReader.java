package com.quizarena.security.tls;

import com.quizarena.protocol.MessageCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads one raw newline-delimited frame at a time while enforcing the shared 16 KiB limit. */
public final class TlsFrameReader {
    private final InputStream input;
    private final int maximumBytes;

    public TlsFrameReader(InputStream input) {
        this(input, MessageCodec.MAX_FRAME_BYTES);
    }

    TlsFrameReader(InputStream input, int maximumBytes) {
        if (input == null) throw new IllegalArgumentException("input is required");
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        this.input = input;
        this.maximumBytes = maximumBytes;
    }

    /** Returns null only for a clean EOF between frames. Blank lines are ignored. */
    public byte[] nextFrame() throws IOException {
        var frame = new ByteArrayOutputStream(Math.min(1024, maximumBytes));
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (frame.size() == 0) return null;
                throw new IOException("TLS connection ended with an incomplete frame");
            }
            if (next == '\n') {
                byte[] bytes = frame.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                if (length == 0) {
                    frame.reset();
                    continue;
                }
                return length == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, length);
            }
            if (frame.size() >= maximumBytes) {
                throw new IOException("TLS frame exceeds " + maximumBytes + " bytes");
            }
            frame.write(next);
        }
    }
}
