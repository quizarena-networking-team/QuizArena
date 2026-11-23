package com.quizarena.client.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Provisional Member 1 lobby payload adapter. A missing or malformed list leaves the last snapshot intact. */
final class LobbyRoster {
    private LobbyRoster() {}

    static Optional<List<String>> read(Map<String, Object> payload) {
        if (!(payload.get("players") instanceof List<?> players)) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>(players.size());
        for (Object player : players) {
            if (!(player instanceof Map<?, ?> entry) || !(entry.get("playerName") instanceof String name) || name.isBlank()) {
                return Optional.empty();
            }
            names.add(name);
        }
        return Optional.of(List.copyOf(names));
    }
}
