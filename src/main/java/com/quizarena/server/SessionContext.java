package com.quizarena.server;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link RequestContext} handed to feature handlers for one request on one connection.
 *
 * Identity getters read the connection's server-assigned binding, never request fields.
 * All delivery methods only enqueue frames; they are thread-safe, never block, and are safe to
 * call later from a worker or timer thread (for example when an asynchronous result completes).
 */
public final class SessionContext implements RequestContext {

    private final ClientSession session;
    private final RoomRegistry rooms;
    private final AtomicBoolean replied = new AtomicBoolean();

    SessionContext(ClientSession session, RoomRegistry rooms) {
        this.session = session;
        this.rooms = rooms;
    }

    ClientSession session() {
        return session;
    }

    RoomRegistry rooms() {
        return rooms;
    }

    /** True once the handler has sent at least one reply (used to decide on an INTERNAL_ERROR fallback). */
    boolean hasReplied() {
        return replied.get();
    }

    @Override
    public String connectionId() {
        return session.connectionId();
    }

    @Override
    public String playerId() {
        return session.playerId();
    }

    @Override
    public String roomCode() {
        return session.roomCode();
    }

    @Override
    public void reply(ProtocolMessage response) {
        replied.set(true);
        session.send(response);
    }

    /** Delivers to a player in the caller's own room only. */
    @Override
    public void sendToPlayer(String playerId, ProtocolMessage event) {
        String room = session.roomCode();
        if (room != null) {
            rooms.sendToPlayer(room, playerId, event);
        }
    }

    @Override
    public void broadcastToRoom(String roomCode, ProtocolMessage event) {
        rooms.broadcast(roomCode, event);
    }
}
