package com.quizarena.server;

import com.quizarena.protocol.MessageHandler;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;

import java.util.Map;
import java.util.Set;

import static com.quizarena.protocol.MessageTypes.*;

/**
 * Member 1's own gameplay requests: PING, JOIN_ROOM and LEAVE_ROOM.
 *
 * <pre>
 * JOIN_ROOM   request  roomCode (envelope), payload.playerName
 *             OK       payload.playerId, payload.playerName (the cleaned name), envelope roomCode (normalized)
 *             ERROR    BAD_REQUEST | ROOM_NOT_FOUND | ROOM_CLOSED (also used when the room is full)
 * LEAVE_ROOM  OK  payload {}      ERROR  INVALID_PLAYER (not in a room) | ROOM_NOT_FOUND (wrong room)
 * PING        PONG with payload {}
 * </pre>
 */
public final class RoomRequestHandler implements MessageHandler {

    public Set<String> supportedTypes() {
        return Set.of(PING, JOIN_ROOM, LEAVE_ROOM);
    }

    @Override
    public void handle(ProtocolMessage request, RequestContext context) {
        if (!(context instanceof SessionContext session)) {
            throw new IllegalStateException("RoomRequestHandler needs the NIO server's SessionContext");
        }
        switch (request.type()) {
            case PING -> context.reply(new ProtocolMessage(PONG, request.requestId(), request.roomCode(),
                    "OK", null, Map.of()));
            case JOIN_ROOM -> join(request, session);
            case LEAVE_ROOM -> leave(request, session);
            default -> context.reply(MessageDispatcher.error(request, "BAD_REQUEST", "Unsupported request type"));
        }
    }

    private void join(ProtocolMessage request, SessionContext context) {
        Object name = request.payload().get("playerName");
        try {
            if (!(name instanceof String playerName)) {
                throw new RoomException("BAD_REQUEST", "playerName must be text");
            }
            context.rooms().join(request.roomCode(), playerName, context.session(), joined ->
                    context.reply(new ProtocolMessage(JOIN_ROOM_RESULT, request.requestId(), joined.roomCode(),
                            "OK", null, Map.of(
                            "playerId", joined.player().id(),
                            "playerName", joined.player().name()))));
        } catch (RoomException rejected) {
            context.reply(MessageDispatcher.error(request, rejected.code(), rejected.getMessage()));
        }
    }

    private void leave(ProtocolMessage request, SessionContext context) {
        String joinedRoom = context.roomCode();
        if (joinedRoom == null) {
            context.reply(MessageDispatcher.error(request, "INVALID_PLAYER", "This connection has not joined a room"));
            return;
        }
        String requested = RoomRegistry.normalizeCode(request.roomCode());
        if (requested != null && !requested.equals(joinedRoom)) {
            context.reply(MessageDispatcher.error(request, "ROOM_NOT_FOUND", "Connection is not in that room"));
            return;
        }
        context.rooms().leave(context.session());
        context.reply(new ProtocolMessage(LEAVE_ROOM_RESULT, request.requestId(), joinedRoom,
                "OK", null, Map.of()));
    }
}
