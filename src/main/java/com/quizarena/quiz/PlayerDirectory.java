package com.quizarena.quiz;

import java.util.List;
import java.util.Optional;

/** Adapter to M1's authoritative room registry. Methods must be thread-safe and non-blocking. */
public interface PlayerDirectory {
    record Player(String id, String name) {
        public Player {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Player identity is required");
            }
        }
    }

    Optional<Player> find(String roomCode, String playerId);
    List<Player> players(String roomCode);
}
