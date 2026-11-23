package com.quizarena.client.player;

import com.quizarena.client.quiz.QuizClientPort;
import com.quizarena.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
        return transport.request(request);
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
