package com.quizarena.server;

import com.quizarena.protocol.MessageCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Reassembles UTF-8 newline-delimited frames from a TCP byte stream (one per client).
 *
 * TCP gives no message boundaries: one read may hold half a frame, exactly one, or
 * several. The decoder owns a single {@link ByteBuffer} that the NIO reactor reads
 * into directly. After each read, {@link #drainFrames()} does the classic
 * flip -> scan -> compact cycle:
 *
 * <pre>
 *   channel.read(writeBuffer())   // buffer is in "write mode" (position = bytes held)
 *   drainFrames():
 *       buffer.flip()             // switch to "read mode"
 *       extract every complete line, leaving any partial tail unread
 *       buffer.compact()          // move the partial tail to the front, back to write mode
 * </pre>
 *
 * Framing happens on raw bytes <em>before</em> UTF-8/JSON decoding. This is what
 * makes a multi-byte character split across two reads safe: '\n' (0x0A) can never
 * appear inside a multi-byte UTF-8 sequence, so a line is only decoded once whole.
 *
 * Not thread-safe: only the selector thread touches a session's decoder.
 */
public final class FrameDecoder {

    /** Thrown when a frame exceeds the protocol limit. The connection must be closed. */
    public static final class FrameTooLargeException extends IOException {
        public FrameTooLargeException(String message) {
            super(message);
        }
    }

    private static final byte NEWLINE = (byte) '\n';
    private static final byte CARRIAGE_RETURN = (byte) '\r';

    private final int maxFrameBytes;
    private final ByteBuffer buffer;

    public FrameDecoder() {
        this(MessageCodec.MAX_FRAME_BYTES);
    }

    public FrameDecoder(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        // Room for the largest legal frame plus an optional '\r' and the '\n'.
        this.buffer = ByteBuffer.allocate(maxFrameBytes + 2);
    }

    /** The buffer to read into. Always in write mode between calls to {@link #drainFrames()}. */
    public ByteBuffer writeBuffer() {
        return buffer;
    }

    /** Number of buffered bytes that do not yet form a complete frame. */
    public int pendingBytes() {
        return buffer.position();
    }

    /**
     * Extracts every complete frame currently buffered, without the line terminator.
     * A trailing '\r' is tolerated (so {@code nc}/telnet work) and blank lines are skipped.
     *
     * @throws FrameTooLargeException if a frame, or an unterminated partial frame, is over the limit
     */
    public List<byte[]> drainFrames() throws FrameTooLargeException {
        List<byte[]> frames = new ArrayList<>(2);
        buffer.flip();
        try {
            int newline;
            while ((newline = indexOfNewline()) >= 0) {
                int length = newline - buffer.position();
                byte[] line = new byte[length];
                buffer.get(line);
                buffer.get(); // consume the '\n'

                if (length > 0 && line[length - 1] == CARRIAGE_RETURN) {
                    length--;
                }
                if (length > maxFrameBytes) {
                    throw new FrameTooLargeException(
                            "Frame exceeds " + maxFrameBytes + " bytes");
                }
                if (length == 0) {
                    continue;
                }
                frames.add(length == line.length ? line : java.util.Arrays.copyOf(line, length));
            }
            // No newline in what is left: it is a partial frame. If it can no longer
            // fit inside the limit it never will, so fail now instead of buffering forever.
            if (buffer.remaining() > maxFrameBytes + 1) {
                throw new FrameTooLargeException(
                        "Unterminated frame exceeds " + maxFrameBytes + " bytes");
            }
        } finally {
            buffer.compact();
        }
        return frames;
    }

    private int indexOfNewline() {
        for (int i = buffer.position(); i < buffer.limit(); i++) {
            if (buffer.get(i) == NEWLINE) {
                return i;
            }
        }
        return -1;
    }
}
