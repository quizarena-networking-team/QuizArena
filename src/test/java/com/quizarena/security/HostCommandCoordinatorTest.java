package com.quizarena.security;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.tls.TlsSessionContext;
import com.quizarena.server.RoomRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class HostCommandCoordinatorTest {
    @Test
    void createsOwnedRoomAndCoordinatesPollLifecycle() {
        var rooms = new RoomRegistry(32, 1);
        var sessionIds = new AtomicLong(100);
        try (var coordinator = coordinator(rooms, (hostId, roomCode) -> sessionIds.incrementAndGet())) {
            var owner = new AuthenticatedHost(7, "owner");
            var connection = new FakeSession(owner);

            coordinator.handle(request(CREATE_ROOM, "r1", null, Map.of()), connection, owner);
            ProtocolMessage created = connection.last();
            assertSuccess(created, CREATE_ROOM_RESULT, "r1");
            String roomCode = (String) created.payload().get("roomCode");
            assertEquals("101", created.payload().get("sessionId"));
            assertTrue(rooms.isOpen(roomCode));

            coordinator.handle(request(START_POLL, "r2", roomCode,
                    Map.of("question", "Choose one", "options", Map.of("A", "Alpha", "B", "Beta"))),
                    connection, owner);
            assertSuccess(connection.last(), START_POLL_RESULT, "r2");
            String pollId = (String) connection.last().payload().get("pollId");

            coordinator.handle(request(START_QUIZ, "r3", roomCode, Map.of("quizId", "1")), connection, owner);
            assertError(connection.last(), START_QUIZ_RESULT, "r3", "BAD_REQUEST");

            coordinator.handle(request(CLOSE_POLL, "r4", roomCode, Map.of("pollId", pollId)), connection, owner);
            assertSuccess(connection.last(), CLOSE_POLL_RESULT, "r4");
            assertEquals(pollId, connection.last().payload().get("pollId"));
        }
        assertFalse(rooms.players("unused").iterator().hasNext());
    }

    @Test
    void rejectsAnotherHostAndRollsBackRoomWhenSessionInsertFails() {
        var rooms = new RoomRegistry(32, 1);
        var capturedRoom = new AtomicReference<String>();
        QuizSessionStore failing = (hostId, roomCode) -> {
            capturedRoom.set(roomCode);
            throw new SQLException("offline details");
        };
        try (var coordinator = coordinator(rooms, failing)) {
            var owner = new AuthenticatedHost(7, "owner");
            var connection = new FakeSession(owner);
            coordinator.handle(request(CREATE_ROOM, "r1", null, Map.of()), connection, owner);
            assertError(connection.last(), CREATE_ROOM_RESULT, "r1", "INTERNAL_ERROR");
            assertFalse(connection.last().payload().toString().contains("offline details"));
            assertFalse(rooms.isOpen(capturedRoom.get()));
        }

        var secondRooms = new RoomRegistry(32, 1);
        try (var coordinator = coordinator(secondRooms, (hostId, roomCode) -> 1)) {
            var owner = new AuthenticatedHost(7, "owner");
            var ownerConnection = new FakeSession(owner);
            coordinator.handle(request(CREATE_ROOM, "r2", null, Map.of()), ownerConnection, owner);
            String roomCode = (String) ownerConnection.last().payload().get("roomCode");

            var intruder = new AuthenticatedHost(8, "intruder");
            var intruderConnection = new FakeSession(intruder);
            coordinator.handle(request(START_POLL, "r3", roomCode,
                    Map.of("question", "No", "options", Map.of("A", "A", "B", "B"))),
                    intruderConnection, intruder);
            assertError(intruderConnection.last(), START_POLL_RESULT, "r3", "UNAUTHORIZED");
        }
    }

    private static HostCommandCoordinator coordinator(RoomRegistry rooms, QuizSessionStore sessions) {
        return new HostCommandCoordinator(rooms, sessions,
                quizId -> { throw new SQLException("quiz loading not expected"); },
                snapshot -> { throw new SQLException("result save not expected"); });
    }

    private static ProtocolMessage request(String type, String requestId, String roomCode,
                                           Map<String, Object> payload) {
        return new ProtocolMessage(type, requestId, roomCode, null, null, payload);
    }

    private static void assertSuccess(ProtocolMessage response, String type, String requestId) {
        assertEquals(type, response.type());
        assertEquals(requestId, response.requestId());
        assertEquals("OK", response.status());
        assertNull(response.errorCode());
    }

    private static void assertError(ProtocolMessage response, String type, String requestId, String code) {
        assertEquals(type, response.type());
        assertEquals(requestId, response.requestId());
        assertEquals("ERROR", response.status());
        assertEquals(code, response.errorCode());
    }

    private static final class FakeSession implements TlsSessionContext {
        private final AuthenticatedHost host;
        private final List<ProtocolMessage> sent = new ArrayList<>();

        FakeSession(AuthenticatedHost host) { this.host = host; }
        @Override public void send(ProtocolMessage message) { sent.add(message); }
        @Override public Optional<AuthenticatedHost> authenticatedHost() { return Optional.of(host); }
        @Override public boolean authenticate(AuthenticatedHost value) { return false; }
        @Override public SocketAddress remoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public String protocol() { return "TLSv1.3"; }
        ProtocolMessage last() { return sent.getLast(); }
    }
}

