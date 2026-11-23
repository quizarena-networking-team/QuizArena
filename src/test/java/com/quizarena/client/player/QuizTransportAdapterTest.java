package com.quizarena.client.player;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.client.quiz.QuizClientPort;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.quizarena.protocol.MessageTypes.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class QuizTransportAdapterTest {
    private static final String ROOM = "ABC123";

    @Test
    void delegatesOriginalRequestsAndPropagatesCorrelationTimeoutAndDisconnect() throws Exception {
        var loopback = InetAddress.getLoopbackAddress();
        try (var listener = new ServerSocket(0, 1, loopback);
             var transport = new PlayerTransport.Tcp(new TcpQuizClient(Duration.ofSeconds(3), Duration.ofSeconds(2)));
             var adapter = new QuizTransportAdapter(transport, ROOM)) {
            listener.setSoTimeout(5_000);
            transport.connect(loopback.getHostAddress(), listener.getLocalPort()).get(5, SECONDS);
            try (var peer = listener.accept()) {
                peer.setSoTimeout(5_000);
                var input = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                QuizClientPort port = adapter;
                var answer = new ProtocolMessage(SUBMIT_ANSWER, "answer-id", ROOM, null, null,
                        Map.of("questionId", "q1", "roundId", "r1", "optionId", "A"));
                var buzzer = new ProtocolMessage(PRESS_BUZZER, "buzzer-id", ROOM, null, null,
                        Map.of("questionId", "q2", "roundId", "r2"));
                var answerFuture = port.request(answer);
                var buzzerFuture = port.request(buzzer);
                assertEquals(answer, read(input));
                assertEquals(buzzer, read(input));

                var buzzerReply = new ProtocolMessage(PRESS_BUZZER_RESULT, buzzer.requestId(), ROOM, "OK", null,
                        Map.of("winner", true, "questionId", "q2", "roundId", "r2"));
                var answerReply = new ProtocolMessage(SUBMIT_ANSWER_RESULT, answer.requestId(), ROOM, "OK", null,
                        Map.of("accepted", true, "questionId", "q1", "roundId", "r1"));
                var output = peer.getOutputStream();
                output.write(MessageCodec.encodeLine(buzzerReply));
                output.write(MessageCodec.encodeLine(answerReply));
                assertEquals(answerReply, answerFuture.get(5, SECONDS));
                assertEquals(buzzerReply, buzzerFuture.get(5, SECONDS));

                var unanswered = port.request(new ProtocolMessage(GET_LEADERBOARD, "no-reply", ROOM, null, null, Map.of()));
                assertEquals("no-reply", read(input).requestId());
                assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class, () -> unanswered.get(5, SECONDS)).getCause());

                var lost = port.request(new ProtocolMessage(GET_LEADERBOARD, "connection-lost", ROOM, null, null, Map.of()));
                assertEquals("connection-lost", read(input).requestId());
                peer.close();
                assertInstanceOf(IOException.class, assertThrows(ExecutionException.class, () -> lost.get(5, SECONDS)).getCause());
            }
        }
    }

    @Test
    void rejectsMisroutedQuizSuccessRepliesAndPreservesServerErrors() throws Exception {
        var loopback = InetAddress.getLoopbackAddress();
        try (var listener = new ServerSocket(0, 1, loopback);
             var transport = new PlayerTransport.Tcp(new TcpQuizClient());
             var adapter = new QuizTransportAdapter(transport, ROOM)) {
            listener.setSoTimeout(5_000);
            transport.connect(loopback.getHostAddress(), listener.getLocalPort()).get(5, SECONDS);
            try (var peer = listener.accept()) {
                peer.setSoTimeout(5_000);
                var input = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                var invalidReplies = List.of(
                        new ProtocolMessage(PONG, "wrong-type", ROOM, "OK", null, Map.of()),
                        new ProtocolMessage(SUBMIT_ANSWER_RESULT, "wrong-round", ROOM, "OK", null,
                                Map.of("accepted", true, "questionId", "q1", "roundId", "older")),
                        new ProtocolMessage(SUBMIT_ANSWER_RESULT, "wrong-room", "OTHER", "OK", null,
                                Map.of("accepted", true, "questionId", "q1", "roundId", "r1")),
                        new ProtocolMessage(SUBMIT_ANSWER_RESULT, "missing-acceptance", ROOM, "OK", null,
                                Map.of("questionId", "q1", "roundId", "r1")));
                for (ProtocolMessage reply : invalidReplies) {
                    var response = adapter.request(answer(reply.requestId()));
                    assertEquals(reply.requestId(), read(input).requestId());
                    peer.getOutputStream().write(MessageCodec.encodeLine(reply));
                    assertInstanceOf(IOException.class,
                            assertThrows(ExecutionException.class, () -> response.get(5, SECONDS)).getCause());
                    assertTrue(transport.isConnected());
                }

                var response = adapter.request(answer("rejected"));
                assertEquals("rejected", read(input).requestId());
                var rejection = new ProtocolMessage(SUBMIT_ANSWER_RESULT, "rejected", ROOM, "ERROR", "QUESTION_CLOSED", Map.of());
                peer.getOutputStream().write(MessageCodec.encodeLine(rejection));
                assertEquals(rejection, response.get(5, SECONDS));
            }
        }
    }

    @Test
    void ordersRoomEventsAndRemovesSubscriptionsWithoutASecondTransportListener() throws Exception {
        try (var transport = new PlayerTransport.Tcp(new TcpQuizClient()); var adapter = new QuizTransportAdapter(transport, ROOM)) {
            List<ProtocolMessage> received = new ArrayList<>();
            Consumer<ProtocolMessage> listener = received::add;
            var oldSubscription = adapter.subscribe(listener);
            assertThrows(IllegalStateException.class, () -> adapter.subscribe(listener));
            var ended = new ProtocolMessage(QUESTION_ENDED, null, ROOM, null, null,
                    Map.of("roundId", "round-1", "correctOptionId", "B"));
            var leaderboard = new ProtocolMessage(LEADERBOARD_UPDATED, null, ROOM, null, null,
                    Map.of("entries", List.of(Map.of("rank", 1, "playerName", "Player", "score", 100))));
            assertTrue(adapter.publish(ended));
            assertFalse(adapter.publish(new ProtocolMessage(QUESTION_ENDED, null, "OTHER", null, null, ended.payload())));
            assertFalse(adapter.publish(new ProtocolMessage(BUZZER_WINNER, null, ROOM, null, null, Map.of("roundId", "round-1"))));
            assertFalse(adapter.publish(new ProtocolMessage(LEADERBOARD_RESULT, "reply", ROOM, "OK", null, leaderboard.payload())));
            assertFalse(adapter.publish(new ProtocolMessage(QUIZ_FINISHED, null, ROOM, null, null,
                    Map.of("persisted", false, "entries", List.of()))));
            assertTrue(adapter.publish(leaderboard));
            assertEquals(List.of(ended, leaderboard), received);

            oldSubscription.close();
            assertFalse(adapter.publish(ended));
            try (var newSubscription = adapter.subscribe(listener)) {
                oldSubscription.close();
                var closed = new ProtocolMessage(ROOM_CLOSED, null, ROOM, null, null, Map.of());
                assertTrue(adapter.publish(closed));
                assertEquals(List.of(ended, leaderboard, closed), received);
                adapter.close();
                assertFalse(adapter.publish(ended));
                assertThrows(IllegalStateException.class, () -> adapter.subscribe(listener));
                var reply = adapter.request(new ProtocolMessage(GET_LEADERBOARD, "closed", ROOM, null, null, Map.of()));
                assertInstanceOf(IOException.class, assertThrows(ExecutionException.class, () -> reply.get(5, SECONDS)).getCause());
            }
        }
    }

    private static ProtocolMessage read(BufferedReader input) throws IOException {
        String line = input.readLine();
        assertNotNull(line);
        return MessageCodec.decodeJson(line.getBytes(StandardCharsets.UTF_8));
    }

    private static ProtocolMessage answer(String requestId) {
        return new ProtocolMessage(SUBMIT_ANSWER, requestId, ROOM, null, null,
                Map.of("questionId", "q1", "roundId", "r1", "optionId", "A"));
    }
}
