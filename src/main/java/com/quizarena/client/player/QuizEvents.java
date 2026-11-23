package com.quizarena.client.player;

import com.quizarena.protocol.ProtocolMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.quizarena.protocol.MessageTypes.*;

/** Checks the wire fields consumed by the existing quiz controllers. */
final class QuizEvents {
    private static final Set<String> OPTIONS = Set.of("A", "B", "C", "D");

    private QuizEvents() {}

    static boolean supports(String type) {
        return switch (type) {
            case QUESTION_STARTED, QUESTION_ENDED, BUZZER_OPENED, BUZZER_WINNER,
                 LEADERBOARD_UPDATED, QUIZ_FINISHED, ROOM_CLOSED -> true;
            default -> false;
        };
    }

    static boolean hasUsablePayload(ProtocolMessage event) {
        Map<String, Object> payload = event.payload();
        return switch (event.type()) {
            case QUESTION_STARTED, BUZZER_OPENED -> isText(payload.get("questionId"))
                    && isText(payload.get("roundId")) && isText(payload.get("text"))
                    && positiveDeadline(payload.get("deadlineEpochMs")) && hasOptions(payload.get("options"));
            case BUZZER_WINNER -> isText(payload.get("roundId")) && isText(payload.get("playerId"))
                    && positiveDeadline(payload.get("deadlineEpochMs"));
            case QUESTION_ENDED -> isText(payload.get("roundId"))
                    && payload.get("correctOptionId") instanceof String option && OPTIONS.contains(option);
            case LEADERBOARD_UPDATED -> hasLeaderboard(payload.get("entries"));
            case QUIZ_FINISHED -> Boolean.TRUE.equals(payload.get("persisted")) && hasLeaderboard(payload.get("entries"));
            case ROOM_CLOSED -> true;
            default -> false;
        };
    }

    private static boolean hasOptions(Object value) {
        if (!(value instanceof Map<?, ?> options)) {
            return false;
        }
        return OPTIONS.stream().allMatch(id -> isText(options.get(id)));
    }

    private static boolean hasLeaderboard(Object value) {
        if (!(value instanceof List<?> entries)) {
            return false;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> fields) || !isText(fields.get("playerName"))
                    || !inIntegerRange(fields.get("rank"), 1) || !inIntegerRange(fields.get("score"), 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static boolean positiveDeadline(Object value) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() > 0;
    }

    private static boolean inIntegerRange(Object value, int minimum) {
        if (!(value instanceof Integer || value instanceof Long)) {
            return false;
        }
        long integer = ((Number) value).longValue();
        return integer >= minimum && integer <= Integer.MAX_VALUE;
    }
}
