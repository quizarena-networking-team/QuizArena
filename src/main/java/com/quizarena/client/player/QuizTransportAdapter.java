package com.quizarena.client.player;

import com.quizarena.client.quiz.QuizClientPort;
import com.quizarena.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static com.quizarena.protocol.MessageTypes.*;

/** A room-scoped quiz port used on JavaFX. Pushes arrive through the shell's ordered room route. */
final class QuizTransportAdapter implements QuizClientPort, AutoCloseable {
    private final PlayerTransport transport;
    private final String roomCode;
    private Registration registration;
    private boolean closed;

    QuizTransportAdapter(PlayerTransport transport, String roomCode) {
        this.transport = transport;
        this.roomCode = roomCode;
    }

    @Override
    public CompletableFuture<ProtocolMessage> request(ProtocolMessage request) {
        if (closed) {
            return CompletableFuture.failedFuture(new IOException("The quiz room session has ended"));
        }
        if (!roomCode.equals(request.roomCode())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The request belongs to another room"));
        }
        String expectedType = switch (request.type()) {
            case SUBMIT_ANSWER -> SUBMIT_ANSWER_RESULT;
            case PRESS_BUZZER -> PRESS_BUZZER_RESULT;
            case GET_LEADERBOARD -> LEADERBOARD_RESULT;
            default -> null;
        };
        var response = transport.request(request);
        return expectedType == null ? response : response.thenApply(reply -> verifyReply(request, expectedType, reply));
    }

    private ProtocolMessage verifyReply(ProtocolMessage request, String expectedType, ProtocolMessage reply) {
        if (reply == null || !expectedType.equals(reply.type())
                || !Objects.equals(request.requestId(), reply.requestId())
                || (reply.roomCode() != null && !roomCode.equals(reply.roomCode()))) {
            throw invalidReply();
        }
        if ("ERROR".equals(reply.status())) {
            return reply;
        }
        if (!"OK".equals(reply.status())) {
            throw invalidReply();
        }
        if (GET_LEADERBOARD.equals(request.type())) {
            return reply;
        }

        String successField = SUBMIT_ANSWER.equals(request.type()) ? "accepted" : "winner";
        if (!Boolean.TRUE.equals(reply.payload().get(successField))
                || !Objects.equals(request.payload().get("questionId"), reply.payload().get("questionId"))
                || !Objects.equals(request.payload().get("roundId"), reply.payload().get("roundId"))) {
            throw invalidReply();
        }
        return reply;
    }

    private static CompletionException invalidReply() {
        return new CompletionException(new IOException("The server reply did not match the quiz request"));
    }

    @Override
    public AutoCloseable subscribe(Consumer<ProtocolMessage> listener) {
        if (closed || registration != null) {
            throw new IllegalStateException("The quiz port is closed or already has a subscriber");
        }
        var current = new Registration(Objects.requireNonNull(listener, "listener"));
        registration = current;
        return () -> {
            if (registration == current) {
                registration = null;
            }
        };
    }

    boolean publish(ProtocolMessage event) {
        if (registration == null || !roomCode.equals(event.roomCode()) || event.status() != null
                || !QuizEvents.hasUsablePayload(event)) {
            return false;
        }
        registration.listener().accept(event);
        return true;
    }

    @Override
    public void close() {
        closed = true;
        registration = null;
    }

    private record Registration(Consumer<ProtocolMessage> listener) {}
}
