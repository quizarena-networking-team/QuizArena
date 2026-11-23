package com.quizarena.quiz;

import com.quizarena.protocol.MessageHandler;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.quizarena.protocol.MessageTypes.*;

/** Register only supportedTypes() on M1's gameplay dispatcher. No host commands are routed here. */
public final class QuizRequestHandler implements MessageHandler {
    private final QuizService quiz;

    public QuizRequestHandler(QuizService quiz) { this.quiz = quiz; }

    public Set<String> supportedTypes() { return Set.of(SUBMIT_ANSWER, PRESS_BUZZER, GET_LEADERBOARD); }

    @Override public void handle(ProtocolMessage request, RequestContext context) {
        String responseType = GET_LEADERBOARD.equals(request.type()) ? LEADERBOARD_RESULT : request.type() + "_RESULT";
        CompletableFuture<Map<String, Object>> result;
        try {
            if (request.requestId() == null || request.requestId().isBlank()) {
                throw new QuizException("BAD_REQUEST", "requestId is required");
            }
            if (!supportedTypes().contains(request.type())) {
                throw new QuizException("UNAUTHORIZED", "Only player quiz requests are accepted here");
            }
            if (!quiz.roomCode().equals(request.roomCode()) || !quiz.roomCode().equals(context.roomCode())) {
                throw new QuizException("ROOM_NOT_FOUND", "Connection is not joined to this room");
            }
            // Identity comes exclusively from M1's trusted connection context, never payload.playerId.
            String playerId = context.playerId();
            result = switch (request.type()) {
                case SUBMIT_ANSWER -> quiz.submitAnswer(playerId, text(request, "questionId"),
                        text(request, "roundId"), text(request, "optionId"));
                case PRESS_BUZZER -> quiz.pressBuzzer(playerId, text(request, "questionId"), text(request, "roundId"));
                case GET_LEADERBOARD -> quiz.leaderboard(playerId);
                default -> throw new QuizException("BAD_REQUEST", "Unsupported request");
            };
        } catch (QuizException failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        result.whenComplete((payload, failure) -> {
            Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
            String code = cause instanceof QuizException expected ? expected.code() : "INTERNAL_ERROR";
            context.reply(new ProtocolMessage(responseType, request.requestId(), quiz.roomCode(),
                    failure == null ? "OK" : "ERROR", failure == null ? null : code,
                    failure == null ? payload : Map.of()));
        });
    }

    private String text(ProtocolMessage request, String field) {
        Object value = request.payload().get(field);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw new QuizException("BAD_REQUEST", "Invalid " + field);
        }
        return text;
    }
}
