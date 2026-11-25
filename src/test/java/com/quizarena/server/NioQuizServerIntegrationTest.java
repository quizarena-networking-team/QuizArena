package com.quizarena.server;

import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class NioQuizServerIntegrationTest {

    @Test
    void pingIsProcessedByWorkerAndReturnsPong() throws Exception {
        try (Harness server = new Harness();
             Client client = new Client(server.port())) {

            ProtocolMessage response =
                    client.exchange(PING, "ping-1", null, Map.of());

            assertEquals(PONG, response.type());
            assertEquals("ping-1", response.requestId());
            assertEquals("OK", response.status());
        }
    }

    @Test
    void twoClientsJoinAndLeaveRoomWithLobbyUpdates() throws Exception {
        try (Harness server = new Harness();
             Client first = new Client(server.port());
             Client second = new Client(server.port())) {

            ProtocolMessage firstJoin =
                    first.exchange(JOIN_ROOM, "join-1", "TEST01",
                            Map.of("playerName", "Alice"));

            assertEquals(JOIN_ROOM_RESULT, firstJoin.type());
            assertEquals("OK", firstJoin.status());
            assertEquals("TEST01", firstJoin.roomCode());

            ProtocolMessage firstLobby = first.readMessage();
            assertEquals(LOBBY_UPDATED, firstLobby.type());
            assertEquals(1, players(firstLobby).size());

            ProtocolMessage secondJoin =
                    second.exchange(JOIN_ROOM, "join-2", "TEST01",
                            Map.of("playerName", "Bob"));

            assertEquals(JOIN_ROOM_RESULT, secondJoin.type());
            assertEquals("OK", secondJoin.status());

            ProtocolMessage secondLobby = second.readMessage();
            assertEquals(LOBBY_UPDATED, secondLobby.type());
            assertEquals(2, players(secondLobby).size());

            ProtocolMessage updatedFirstLobby = first.readMessage();
            assertEquals(LOBBY_UPDATED, updatedFirstLobby.type());
            assertEquals(2, players(updatedFirstLobby).size());

            ProtocolMessage leave =
                    second.exchange(LEAVE_ROOM, "leave-2", "TEST01", Map.of());

            assertEquals(LEAVE_ROOM_RESULT, leave.type());
            assertEquals("OK", leave.status());

            ProtocolMessage afterLeave = first.readMessage();
            assertEquals(LOBBY_UPDATED, afterLeave.type());
            assertEquals(1, players(afterLeave).size());
        }
    }

    @Test
    void disconnectRemovesPlayerAndBroadcastsUpdatedLobby() throws Exception {
        try (Harness server = new Harness();
             Client first = new Client(server.port());
             Client second = new Client(server.port())) {

            first.exchange(JOIN_ROOM, "join-a", "TEST01",
                    Map.of("playerName", "Alice"));
            assertEquals(LOBBY_UPDATED, first.readMessage().type());

            second.exchange(JOIN_ROOM, "join-b", "TEST01",
                    Map.of("playerName", "Bob"));
            assertEquals(LOBBY_UPDATED, second.readMessage().type());

            ProtocolMessage twoPlayers = first.readMessage();
            assertEquals(LOBBY_UPDATED, twoPlayers.type());
            assertEquals(2, players(twoPlayers).size());

            // Closing the TCP connection should remove the player from the room.
            first.close();

            ProtocolMessage afterDisconnect = second.readMessage();
            assertEquals(LOBBY_UPDATED, afterDisconnect.type());
            assertEquals(1, players(afterDisconnect).size());
        }
    }

    private static List<?> players(ProtocolMessage message) {
        Object value = message.payload().get("players");
        assertInstanceOf(List.class, value);
        return (List<?>) value;
    }

    static final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader input;

        Client(int port) throws Exception {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
        }

        ProtocolMessage exchange(
                String type,
                String requestId,
                String roomCode,
                Map<String, Object> payload) throws Exception {

            ProtocolMessage request =
                    new ProtocolMessage(type, requestId, roomCode, null, null, payload);

            socket.getOutputStream().write(MessageCodec.encodeLine(request));
            socket.getOutputStream().flush();

            return readMessage();
        }

        ProtocolMessage readMessage() throws Exception {
            String line = input.readLine();
            assertNotNull(line, "Server closed the connection unexpectedly");
            return MessageCodec.decodeJson(line.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    static final class Harness implements AutoCloseable {
        private final NioQuizServer server;

        Harness() throws Exception {
            RoomRegistry rooms = new RoomRegistry(8, 2);
            rooms.createRoom("TEST01");

            MessageDispatcher dispatcher = new MessageDispatcher();
            RoomRequestHandler handler = new RoomRequestHandler();
            dispatcher.register(handler.supportedTypes(), handler);

            ServerConfig config = ServerConfig.defaults()
                    .withBindHost("127.0.0.1")
                    .withTcpPort(0)
                    .withIdleTimeout(Duration.ZERO)
                    .withLogIo(false);

            server = new NioQuizServer(
                    config, dispatcher, rooms, new NetworkEventBus());

            server.start();
        }

        int port() {
            return server.boundPort();
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
