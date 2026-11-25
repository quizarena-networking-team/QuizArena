package com.quizarena.metrics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * One real, timestamped networking observation (accept, read, write, disconnect, ...).
 *
 * Published by the NIO server (Member 1) and consumed by the console log and,
 * later, by Member 4's host-only network inspector. Events are measured, never
 * invented: {@code bytes} is the number of bytes actually moved by the channel.
 */
public record NetworkEvent(
        Instant timestamp,
        Kind kind,
        String connectionId,
        String remote,
        long bytes,
        String detail
) {
    public enum Kind {
        SERVER_STARTED,
        SERVER_STOPPED,
        ACCEPT,
        READ,
        WRITE,
        FRAME_REJECTED,
        DISCONNECT,
        ERROR
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public NetworkEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(kind, "kind");
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes cannot be negative");
        }
    }

    /** Console-friendly single line, e.g. {@code 12:00:01.250 READ conn=3fa9c1d2 remote=/127.0.0.1:50122 bytes=87}. */
    @Override
    public String toString() {
        StringBuilder line = new StringBuilder(TIME.format(timestamp)).append(' ').append(kind);
        if (connectionId != null) {
            line.append(" conn=").append(connectionId);
        }
        if (remote != null) {
            line.append(" remote=").append(remote);
        }
        if (bytes > 0) {
            line.append(" bytes=").append(bytes);
        }
        if (detail != null && !detail.isBlank()) {
            line.append(" - ").append(detail);
        }
        return line.toString();
    }
}
