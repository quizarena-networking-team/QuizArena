package com.quizarena.quiz;

import com.quizarena.protocol.ProtocolMessage;

/** M1 adapter: enqueue in order; never perform socket writes or other blocking work here. */
@FunctionalInterface
public interface GameEventSink {
    void broadcast(ProtocolMessage event);
}
