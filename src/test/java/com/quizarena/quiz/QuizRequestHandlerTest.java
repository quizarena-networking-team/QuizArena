package com.quizarena.quiz;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.quizarena.protocol.MessageTypes.*;
import static com.quizarena.quiz.QuizTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class QuizRequestHandlerTest {
    static final class Context implements RequestContext {
        final CompletableFuture<ProtocolMessage> response = new CompletableFuture<>();
        String player = "p0";
        String room = ROOM;
        public String connectionId() { return "trusted-connection"; }
        public String playerId() { return player; }
        public String roomCode() { return room; }
        public void reply(ProtocolMessage reply) { response.complete(reply); }
        public void sendToPlayer(String playerId, ProtocolMessage event) { fail("Not used by handler"); }
        public void broadcastToRoom(String roomCode, ProtocolMessage event) { fail("Service uses event sink"); }
    }

    @Test void handlerUsesConnectionIdentityAndPreservesRequestId() {
        try (var h = new Harness()) {
            h.start();
            var context = new Context();
            new QuizRequestHandler(h.service).handle(new ProtocolMessage(SUBMIT_ANSWER, "r1", ROOM, null, null,
                    Map.of("questionId", "1", "roundId", h.round(), "optionId", "A", "playerId", "p1", "score", 999)), context);
            var response = await(context.response);
            assertEquals(SUBMIT_ANSWER_RESULT, response.type());
            assertEquals("r1", response.requestId());
            assertEquals("OK", response.status());
            error("ANSWER_ALREADY_SUBMITTED", h.service.submitAnswer("p0", "1", h.round(), "B"));
            await(h.service.submitAnswer("p1", "1", h.round(), "B"));
        }
    }

    @Test void hostCommandCannotMutateGameOnPlayerHandler() {
        try (var h = new Harness()) {
            var context = new Context();
            new QuizRequestHandler(h.service).handle(new ProtocolMessage(START_QUIZ, "host", ROOM, null, null,
                    Map.of("quizId", "1")), context);
            assertEquals("UNAUTHORIZED", await(context.response).errorCode());
            assertEquals(QuizState.LOBBY, h.service.state());
        }
    }

    @Test void wrongRoomMissingIdentityAndMalformedPayloadAreRejected() {
        try (var h = new Harness()) {
            h.start();
            var handler = new QuizRequestHandler(h.service);
            var wrongRoom = new Context(); wrongRoom.room = "OTHER";
            handler.handle(new ProtocolMessage(GET_LEADERBOARD, "r1", ROOM, null, null, Map.of()), wrongRoom);
            assertEquals("ROOM_NOT_FOUND", await(wrongRoom.response).errorCode());
            var guest = new Context(); guest.player = null;
            handler.handle(new ProtocolMessage(GET_LEADERBOARD, "r2", ROOM, null, null, Map.of()), guest);
            assertEquals("INVALID_PLAYER", await(guest.response).errorCode());
            var malformed = new Context();
            handler.handle(new ProtocolMessage(SUBMIT_ANSWER, "r3", ROOM, null, null,
                    Map.of("questionId", 1)), malformed);
            assertEquals("BAD_REQUEST", await(malformed.response).errorCode());
            var noRequestId = new Context();
            handler.handle(new ProtocolMessage(GET_LEADERBOARD, null, ROOM, null, null, Map.of()), noRequestId);
            assertEquals("BAD_REQUEST", await(noRequestId.response).errorCode());
        }
    }

    @Test void leaderboardUsesProtocolExceptionResponseName() {
        try (var h = new Harness()) {
            h.start();
            var context = new Context();
            new QuizRequestHandler(h.service).handle(new ProtocolMessage(GET_LEADERBOARD, "rank", ROOM, null, null, Map.of()), context);
            assertEquals(LEADERBOARD_RESULT, await(context.response).type());
        }
    }
}
