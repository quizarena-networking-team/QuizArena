package com.quizarena.poll;

import java.util.Map;

/** Public poll details and aggregate counts captured at a single point in time. */
public record PollSnapshot(String pollId, String question, Map<String, String> options,
                           Map<String, Integer> counts, State state) {
    public enum State { OPEN, CLOSED }

    public PollSnapshot {
        options = Map.copyOf(options);
        counts = Map.copyOf(counts);
    }
}
