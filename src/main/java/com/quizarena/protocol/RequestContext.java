package com.quizarena.protocol;

/**
 * Information and reply methods supplied by
 * the networking layer to request handlers.
 */
public interface RequestContext {

    String connectionId();

    // Null before a player joins.
    String playerId();

    // Null before a connection joins a room.
    String roomCode();

    void reply(ProtocolMessage response);

    void sendToPlayer(
            String playerId,
            ProtocolMessage event
    );

    void broadcastToRoom(
            String roomCode,
            ProtocolMessage event
    );
}
