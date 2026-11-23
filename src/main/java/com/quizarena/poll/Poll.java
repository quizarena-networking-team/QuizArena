package com.quizarena.poll;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Mutable poll state accessed only under its owning service's lock after publication. */
final class Poll {
    private final String id = UUID.randomUUID().toString();
    private final String question;
    private final Map<String, String> options;
    private final Map<String, String> votes = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private PollSnapshot.State state = PollSnapshot.State.OPEN;

    Poll(String question, Map<String, String> options) {
        this.question = question;
        this.options = Map.copyOf(options);
        for (String optionId : this.options.keySet()) {
            counts.put(optionId, 0);
        }
    }

    String id() {
        return id;
    }

    boolean isOpen() {
        return state == PollSnapshot.State.OPEN;
    }

    void recordVote(String playerId, String optionId) {
        Integer count = counts.get(optionId);
        if (count == null) {
            throw new PollException("BAD_REQUEST", "Unknown poll option");
        }
        if (votes.putIfAbsent(playerId, optionId) != null) {
            throw new PollException("ALREADY_VOTED", "Player already voted in this poll");
        }
        counts.put(optionId, count + 1);
    }

    void close() {
        state = PollSnapshot.State.CLOSED;
    }

    Map<String, Integer> countsSnapshot() {
        return Map.copyOf(counts);
    }

    PollSnapshot snapshot() {
        return new PollSnapshot(id, question, options, counts, state);
    }
}
