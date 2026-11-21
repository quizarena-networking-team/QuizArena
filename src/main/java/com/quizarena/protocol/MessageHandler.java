package com.quizarena.protocol;

/**
 * Contract for handling incoming application requests.
 *
 * Feature modules implement this interface.
 */
@FunctionalInterface
public interface MessageHandler {

    void handle(
            ProtocolMessage request,
            RequestContext context
    );
}
