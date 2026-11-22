package com.quizarena.quiz;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.quizarena.protocol.MessageTypes.*;
import static com.quizarena.quiz.QuizTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP verification through a test-only gateway on an ephemeral loopback port.
 * This fixture is not M1's production NIO server and does not implement room join/authentication.
 */
class QuizSocketTest {
    @Test void fiveSocketClientsCompeteAndOnlyWinnerReceivesBonus() throws Exception {
        try (var h = new Harness(); var gateway = new Gateway(new QuizRequestHandler(h.service))) {
            h.start(); h.expire();
            await(h.service.openBuzzer(Duration.ofSeconds(10), Duration.ofSeconds(15)));
            String round = h.round();
            var replies = new ProtocolMessage[5];
            var clients = new ArrayList<Client>();
            try {
                for (int i = 0; i < 5; i++) clients.add(new Client(gateway.port()));
                race(5, i -> CompletableFuture.supplyAsync(() -> {
                    replies[i] = clients.get(i).exchange(PRESS_BUZZER, "buzz-" + i,
                            Map.of("questionId", "4", "roundId", round));
                    return replies[i];
                }));
                int winner = -1;
                for (int i = 0; i < 5; i++) {
                    assertEquals("buzz-" + i, replies[i].requestId());
                    assertEquals(PRESS_BUZZER_RESULT, replies[i].type());
                    if ("OK".equals(replies[i].status())) {
                        assertEquals(-1, winner, "More than one socket won");
                        winner = i;
                    } else assertEquals("BUZZER_CLOSED", replies[i].errorCode());
                }
                assertNotEquals(-1, winner);
                int loser = (winner + 1) % 5;
                var denied = clients.get(loser).exchange(SUBMIT_ANSWER, "loser-answer",
                        Map.of("questionId", "4", "roundId", round, "optionId", "A"));
                assertEquals("UNAUTHORIZED", denied.errorCode());
                var accepted = clients.get(winner).exchange(SUBMIT_ANSWER, "winner-answer",
                        Map.of("questionId", "4", "roundId", round, "optionId", "A"));
                assertEquals("OK", accepted.status());
                var leaderboard = clients.get(winner).exchange(GET_LEADERBOARD, "ranking", Map.of());
                assertEquals(LEADERBOARD_RESULT, leaderboard.type());
                var entries = (List<?>) leaderboard.payload().get("entries");
                assertEquals(50, ((Map<?, ?>) entries.getFirst()).get("score"));
                assertEquals(1, h.events.stream().filter(e -> e.type().equals(BUZZER_WINNER)).count());
            } finally { for (var client : clients) client.close(); }
        }
    }

    static final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader input;
        Client(int port) throws Exception {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }
        ProtocolMessage exchange(String type, String id, Map<String, Object> payload) {
            try {
                socket.getOutputStream().write(MessageCodec.encodeLine(new ProtocolMessage(type, id, ROOM, null, null, payload)));
                socket.getOutputStream().flush();
                String line = input.readLine();
                assertNotNull(line, "Test gateway disconnected unexpectedly");
                return MessageCodec.decodeJson(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
        public void close() throws Exception { socket.close(); }
    }

    static final class Gateway implements AutoCloseable {
        final ServerSocket server;
        final java.util.concurrent.ExecutorService io = Executors.newFixedThreadPool(6);
        final List<Socket> sessions = new CopyOnWriteArrayList<>();
        Gateway(QuizRequestHandler handler) throws Exception {
            server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            io.submit(() -> {
                try {
                    for (int i = 0; i < 5; i++) {
                        Socket socket = server.accept();
                        sessions.add(socket);
                        String identity = "p" + i; // Assigned by the fixture, never taken from a request.
                        io.submit(() -> serve(handler, socket, identity));
                    }
                } catch (Exception failure) {
                    if (!server.isClosed()) throw new AssertionError(failure);
                }
            });
        }
        int port() { return server.getLocalPort(); }
        void serve(QuizRequestHandler handler, Socket socket, String identity) {
            try (socket; var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    var reply = new CompletableFuture<ProtocolMessage>();
                    handler.handle(MessageCodec.decodeJson(line.getBytes(StandardCharsets.UTF_8)), new RequestContext() {
                        public String connectionId() { return identity; }
                        public String playerId() { return identity; }
                        public String roomCode() { return ROOM; }
                        public void reply(ProtocolMessage response) { reply.complete(response); }
                        public void sendToPlayer(String player, ProtocolMessage event) { throw new UnsupportedOperationException(); }
                        public void broadcastToRoom(String room, ProtocolMessage event) { throw new UnsupportedOperationException(); }
                    });
                    socket.getOutputStream().write(MessageCodec.encodeLine(reply.get(5, TimeUnit.SECONDS)));
                    socket.getOutputStream().flush();
                }
            } catch (Exception failure) {
                if (!socket.isClosed()) throw new AssertionError(failure);
            }
        }
        public void close() throws Exception {
            server.close();
            for (var socket : sessions) socket.close();
            io.shutdownNow();
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
