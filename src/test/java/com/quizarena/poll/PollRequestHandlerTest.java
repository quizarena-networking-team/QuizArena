package com.quizarena.poll;

import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.quizarena.poll.PollTestSupport.*;
import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PollRequestHandlerTest {
    @Test
    void usesTrustedIdentityAndPreservesRequestId() {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            var disguised = new ProtocolMessage(SUBMIT_VOTE, "vote-1", ROOM, null, null,
                    Map.of("pollId", pollId, "optionId", "B", "playerId", "p2", "role", "host", "counts", Map.of("B", 500)));

            assertEquals(new ProtocolMessage(SUBMIT_VOTE_RESULT, "vote-1", ROOM, "OK", null,
                    Map.of("pollId", pollId, "optionId", "B", "accepted", true)), fixture.handle("p1", disguised));
            assertError("vote-2", "ALREADY_VOTED", fixture.handle("p1", vote("vote-2", pollId, "A")));
            assertEquals("OK", fixture.handle("p2", vote("vote-3", pollId, "A")).status());
            assertEquals(Map.of("A", 1, "B", 1, "C", 0), fixture.service.currentPoll().orElseThrow().counts());
        }
    }

    @Test
    void simultaneousVotesFromOnePlayerProduceOneAcceptance() throws Exception {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            var replies = race(2, i -> fixture.handle("p1", vote("race-" + i, pollId, i == 0 ? "A" : "B")));
            var accepted = replies.stream().filter(response -> "OK".equals(response.status())).toList();
            assertEquals(1, accepted.size());
            var denied = replies.stream().filter(response -> "ERROR".equals(response.status())).toList();
            assertEquals(1, denied.size());
            assertEquals("ALREADY_VOTED", denied.getFirst().errorCode());
            assertEquals(List.of("race-0", "race-1"), replies.stream().map(ProtocolMessage::requestId).toList());
            assertEquals(1, fixture.service.currentPoll().orElseThrow().counts().get(accepted.getFirst().payload().get("optionId")));
            assertEquals(List.of(POLL_STARTED, POLL_RESULTS), fixture.events.stream().map(ProtocolMessage::type).toList());
        }
    }

    @Test
    void simultaneousPlayersKeepAccurateTotalsAndOrderedImmutableBroadcasts() throws Exception {
        int players = 48;
        try (var fixture = new Fixture()) {
            for (int i = 0; i < players; i++) {
                fixture.directory.join("guest-" + i);
            }
            String pollId = fixture.start().pollId();
            var replies = race(players, i -> fixture.handle("guest-" + i,
                    vote("concurrent-" + i, pollId, i % 3 == 0 ? "B" : "A")));
            for (int i = 0; i < players; i++) {
                assertEquals("OK", replies.get(i).status());
                assertEquals("concurrent-" + i, replies.get(i).requestId());
            }

            Map<String, Integer> expected = Map.of("A", 32, "B", 16, "C", 0);
            assertEquals(expected, fixture.service.currentPoll().orElseThrow().counts());
            assertEquals(players + 1, fixture.events.size());
            for (int i = 1; i < fixture.events.size(); i++) {
                ProtocolMessage event = fixture.events.get(i);
                assertEquals(POLL_RESULTS, event.type());
                assertEquals(ROOM, event.roomCode());
                assertNull(event.requestId());
                assertNull(event.status());
                assertNull(event.errorCode());
                assertEquals(Set.of("pollId", "counts"), event.payload().keySet());
                assertEquals(pollId, event.payload().get("pollId"));
                var counts = assertInstanceOf(Map.class, event.payload().get("counts"));
                assertEquals(i, (int) counts.get("A") + (int) counts.get("B") + (int) counts.get("C"));
                assertEquals(0, counts.get("C"));
                assertThrows(UnsupportedOperationException.class, counts::clear);
            }
            assertEquals(expected, fixture.events.getLast().payload().get("counts"));
        }
    }

    @Test
    void rejectsUnknownDisconnectedAndUnjoinedPlayers() {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            assertError("unknown", "INVALID_PLAYER", fixture.handle("unknown", vote("unknown", pollId, "A")));
            fixture.directory.join("left");
            assertNotNull(fixture.directory.joined.remove("left"));
            assertError("left", "INVALID_PLAYER", fixture.handle("left", vote("left", pollId, "A")));
            var spoofed = new ProtocolMessage(SUBMIT_VOTE, "unjoined", ROOM, null, null,
                    Map.of("pollId", pollId, "optionId", "A", "playerId", "p1"));
            assertError("unjoined", "INVALID_PLAYER", fixture.handle(null, spoofed));
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    void rejectsUnknownPollAndOptionWithoutConsumingTheVote() {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            assertError("bad-poll", "BAD_REQUEST", fixture.handle("p1", vote("bad-poll", "other-poll", "A")));
            assertError("bad-option", "BAD_REQUEST", fixture.handle("p1", vote("bad-option", pollId, "Z")));
            assertEquals(1, fixture.events.size());
            assertEquals("OK", fixture.handle("p1", vote("valid", pollId, "A")).status());
        }
    }

    @Test
    void rejectsVotesBeforeStartAfterCloseAndAfterRoomShutdown() {
        try (var fixture = new Fixture()) {
            assertError("no-poll", POLL_CLOSED, fixture.handle("p1", vote("no-poll", "missing", "A")));
            String pollId = fixture.start().pollId();
            fixture.service.closePoll(pollId);
            assertError("closed-poll", POLL_CLOSED, fixture.handle("p1", vote("closed-poll", pollId, "A")));
            fixture.service.close();
            assertError("closed-room", "ROOM_CLOSED", fixture.handle("p1", vote("closed-room", pollId, "A")));
            assertEquals(List.of(POLL_STARTED, POLL_CLOSED), fixture.events.stream().map(ProtocolMessage::type).toList());
        }
    }

    @Test
    void requiresBothRequestedRoomAndTrustedRoomToMatch() {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            var wrongRoom = new ProtocolMessage(SUBMIT_VOTE, "wrong-room", "OTHER", null, null,
                    Map.of("pollId", pollId, "optionId", "A"));
            assertError("wrong-room", "ROOM_NOT_FOUND", fixture.handle("p1", wrongRoom));
            assertError("wrong-context", "ROOM_NOT_FOUND", fixture.handle("p1", "OTHER", vote("wrong-context", pollId, "A")));
            assertError("no-room", "ROOM_NOT_FOUND", fixture.handle("p1", null, vote("no-room", pollId, "A")));
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    void rejectsMalformedPayloadsAndEnvelopesSafely() {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            List<Map<String, Object>> malformed = List.of(Map.of(), Map.of("pollId", 17, "optionId", "A"),
                    Map.of("pollId", pollId, "optionId", List.of("A")), Map.of("pollId", pollId, "optionId", " "));
            for (Map<String, Object> payload : malformed) {
                var request = new ProtocolMessage(SUBMIT_VOTE, "bad-payload", ROOM, null, null, payload);
                assertError("bad-payload", "BAD_REQUEST", fixture.handle("p1", request));
            }
            Map<String, Object> payload = Map.of("pollId", pollId, "optionId", "A");
            assertError(null, "BAD_REQUEST", fixture.handle("p1", new ProtocolMessage(SUBMIT_VOTE, null, ROOM, null, null, payload)));
            assertError("no-room", "BAD_REQUEST", fixture.handle("p1", new ProtocolMessage(SUBMIT_VOTE, "no-room", null, null, null, payload)));
            assertError("response", "BAD_REQUEST", fixture.handle("p1", new ProtocolMessage(SUBMIT_VOTE, "response", ROOM, "OK", null, payload)));
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    void gameplayHandlerCannotRunHostCommandsEvenWithClaimedHostRole() {
        try (var fixture = new Fixture()) {
            assertEquals(Set.of(SUBMIT_VOTE), fixture.handler.supportedTypes());
            var start = new ProtocolMessage(START_POLL, "host-start", ROOM, null, null,
                    Map.of("role", "host", "question", QUESTION, "options", OPTIONS));
            assertEquals(new ProtocolMessage(START_POLL_RESULT, "host-start", ROOM, "ERROR", "UNAUTHORIZED", Map.of()),
                    fixture.handle("p1", start));
            assertTrue(fixture.service.currentPoll().isEmpty());

            PollSnapshot poll = fixture.start();
            var close = new ProtocolMessage(CLOSE_POLL, "host-close", ROOM, null, null,
                    Map.of("role", "host", "pollId", poll.pollId()));
            assertEquals(new ProtocolMessage(CLOSE_POLL_RESULT, "host-close", ROOM, "ERROR", "UNAUTHORIZED", Map.of()),
                    fixture.handle("p1", close));
            assertEquals(poll, fixture.service.currentPoll().orElseThrow());
            assertEquals(1, fixture.events.size());
        }
    }

    private static void assertError(String requestId, String code, ProtocolMessage reply) {
        assertEquals(SUBMIT_VOTE_RESULT, reply.type());
        assertEquals(requestId, reply.requestId());
        assertEquals("ERROR", reply.status());
        assertEquals(code, reply.errorCode());
        assertEquals(Map.of(), reply.payload());
    }
}
