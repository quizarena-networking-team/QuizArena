package com.quizarena.quiz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owned exclusively by QuizService's room lock. Equal scores share competition rank. */
public final class ScoreService {
    public record Entry(String playerId, String playerName, int score,
                        int correctCount, int answeredCount, int rank) {
        public Map<String, Object> publicPayload() {
            return Map.of("playerId", playerId, "playerName", playerName, "score", score, "rank", rank);
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public void register(PlayerDirectory.Player player) {
        entries.putIfAbsent(player.id(), new Entry(player.id(), player.name(), 0, 0, 0, 0));
    }

    public void record(String playerId, boolean correct, int points) {
        var old = entries.get(playerId);
        if (old == null) throw new QuizException("INVALID_PLAYER", "Player is not in this quiz");
        entries.put(playerId, new Entry(old.playerId(), old.playerName(),
                Math.addExact(old.score(), correct ? points : 0),
                old.correctCount() + (correct ? 1 : 0), old.answeredCount() + 1, 0));
    }

    public List<Entry> snapshot() {
        var sorted = entries.values().stream().sorted(Comparator.comparingInt(Entry::score).reversed()
                .thenComparing(Entry::playerName).thenComparing(Entry::playerId)).toList();
        var result = new ArrayList<Entry>();
        int previousScore = -1;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            var entry = sorted.get(i);
            if (entry.score() != previousScore) rank = i + 1;
            previousScore = entry.score();
            result.add(new Entry(entry.playerId(), entry.playerName(), entry.score(),
                    entry.correctCount(), entry.answeredCount(), rank));
        }
        return List.copyOf(result);
    }
}
