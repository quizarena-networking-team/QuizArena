package com.quizarena.quiz;

import java.time.Instant;
import java.util.List;

/** Immutable database handoff. Contains no sockets or mutable game objects. */
public record ResultSnapshot(long sessionId, String roomCode, String quizId,
                             Instant startedAt, Instant endedAt, List<ScoreService.Entry> entries) {
    public ResultSnapshot {
        if (sessionId <= 0 || roomCode == null || roomCode.isBlank() || quizId == null || quizId.isBlank()
                || startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Invalid completed session");
        }
        entries = List.copyOf(entries);
    }
}
