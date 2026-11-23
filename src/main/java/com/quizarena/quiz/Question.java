package com.quizarena.quiz;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Server-only question. Never serialize this record into a public event. */
public record Question(String id, String text, Map<String, String> options,
                       String correctOption, int points, Duration duration) {
    public Question {
        if (id == null || id.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Question ID and text are required");
        }
        options = Map.copyOf(options);
        if (!options.keySet().equals(Set.of("A", "B", "C", "D"))
                || options.values().stream().anyMatch(String::isBlank)
                || !options.containsKey(correctOption)) {
            throw new IllegalArgumentException("Four nonempty options A-D and a valid correct option are required");
        }
        if (points < 0 || points > 1_000_000 || duration == null
                || duration.compareTo(Duration.ofSeconds(1)) < 0
                || duration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid question points or duration (1-600 seconds)");
        }
    }
}
