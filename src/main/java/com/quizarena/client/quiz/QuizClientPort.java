package com.quizarena.client.quiz;

import com.quizarena.protocol.ProtocolMessage;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Adapter implemented by M2's existing TCP client; this module does not open another socket. */
public interface QuizClientPort {
    /** Non-blocking, correlates requestId, and completes exceptionally on timeout/disconnection. */
    CompletableFuture<ProtocolMessage> request(ProtocolMessage request);
    /** Delivers room events in wire order. Closing the subscription removes its listener. */
    AutoCloseable subscribe(Consumer<ProtocolMessage> listener);
}
