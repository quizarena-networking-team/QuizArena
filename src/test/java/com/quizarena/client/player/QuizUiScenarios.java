package com.quizarena.client.player;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.client.quiz.LeaderboardController;
import com.quizarena.protocol.ProtocolMessage;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.quizarena.client.player.PlayerUiScenarios.*;
import static com.quizarena.protocol.MessageTypes.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/** Desktop integration scenarios for the real quiz FXML mounted in the player shell. */
final class QuizUiScenarios {
    private QuizUiScenarios() {}

    static void loopback() throws Exception {
        try (var listener = new ServerSocket(0, 1, LOOPBACK);
             var ui = Ui.open(new PlayerTransport.Tcp(new TcpQuizClient()))) {
            listener.setSoTimeout(6_000);
            onFx(() -> ui.startConnection(LOOPBACK.getHostAddress(), listener.getLocalPort()));
            try (var peer = listener.accept()) {
                peer.setSoTimeout(6_000);
                var input = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                ui.awaitText("connectionStatus", "Connected"::equals);
                onFx(() -> ui.startJoin(ROOM, "Quiz player"));
                ProtocolMessage join = read(input);
                assertEquals(JOIN_ROOM, join.type());
                send(peer, ok(join, JOIN_ROOM_RESULT, Map.of("playerId", "assigned-p7")));
                ui.awaitText("lobbyRoom", ROOM::equals);
                Button pollLink = fxResult(() -> ui.button("openPollButton"));

                send(peer, question(QUESTION_STARTED, "q1", "round-1", "TCP quiz question"));
                awaitQuiz(ui, "TCP quiz question");
                awaitEnabled(() -> quizNode(ui, "optionB"));
                onFx(() -> {
                    assertEquals("assigned-p7", ui.session().joinedRoom().orElseThrow().playerId());
                    assertEquals("Choose one answer.", questionStatus(ui).getText());
                    assertTrue(quizNode(ui, "buzz").isDisabled());
                    answer(ui, "B");
                });
                ProtocolMessage answer = read(input);
                assertEquals(SUBMIT_ANSWER, answer.type());
                assertEquals(ROOM, answer.roomCode());
                assertFalse(answer.requestId().isBlank());
                assertEquals(Map.of("questionId", "q1", "roundId", "round-1", "optionId", "B"), answer.payload());
                send(peer, ok(answer, SUBMIT_ANSWER_RESULT, Map.of("accepted", true, "questionId", "q1", "roundId", "round-1")));
                awaitValue(() -> questionStatus(ui).textProperty(), value -> value.startsWith("Answer received"));
                send(peer, ended("q1", "round-1", "B"));
                awaitValue(() -> questionStatus(ui).textProperty(), "Correct answer: B"::equals);

                send(peer, event(LEADERBOARD_UPDATED, Map.of("entries", entries(100))));
                send(peer, question(BUZZER_OPENED, "q2", "round-2", "Buzzer question"));
                awaitQuiz(ui, "Buzzer question");
                awaitEnabled(() -> quizNode(ui, "buzz"));
                onFx(() -> {
                    assertTrue(quizNode(ui, "optionA").isDisabled());
                    assertEquals(100, firstScore(ui));
                    ((Button) quizNode(ui, "buzz")).fire();
                });
                ProtocolMessage pressed = read(input);
                assertEquals(PRESS_BUZZER, pressed.type());
                assertNotEquals(answer.requestId(), pressed.requestId());
                assertEquals(Map.of("questionId", "q2", "roundId", "round-2"), pressed.payload());
                send(peer, ok(pressed, PRESS_BUZZER_RESULT, Map.of("winner", true, "questionId", "q2", "roundId", "round-2")));
                send(peer, winner("q2", "round-2", "assigned-p7"));
                awaitEnabled(() -> quizNode(ui, "optionA"));
                onFx(() -> answer(ui, "A"));
                ProtocolMessage buzzerAnswer = read(input);
                assertEquals(SUBMIT_ANSWER, buzzerAnswer.type());
                assertEquals(Map.of("questionId", "q2", "roundId", "round-2", "optionId", "A"), buzzerAnswer.payload());
                send(peer, ok(buzzerAnswer, SUBMIT_ANSWER_RESULT, Map.of("accepted", true, "questionId", "q2", "roundId", "round-2")));
                send(peer, ended("q2", "round-2", "A"));
                send(peer, event(QUIZ_FINISHED, Map.of("quizId", "quiz-1", "persisted", true, "entries", entries(150))));
                awaitValue(() -> ((Label) quizNode(ui, "heading")).textProperty(), "Final results — saved"::equals);

                send(peer, event(LEADERBOARD_UPDATED, Map.of("entries", entries(1))));
                send(peer, pollStarted("shared-poll", "Poll on the same socket"));
                awaitValue(pollLink::visibleProperty, Boolean.TRUE::equals);
                onFx(() -> {
                    assertFinalQuiz(ui);
                    ui.click("quizLobbyButton");
                    ui.assertPage("lobbyView");
                    ui.click("openPollButton");
                    ui.assertPage("pollView");
                    ui.submit("A");
                });
                ProtocolMessage vote = read(input);
                assertEquals(SUBMIT_VOTE, vote.type());
                assertEquals(Map.of("pollId", "shared-poll", "optionId", "A"), vote.payload());
                send(peer, ok(vote, SUBMIT_VOTE_RESULT, Map.of("pollId", "shared-poll", "optionId", "A", "accepted", true)));
                send(peer, counts(POLL_CLOSED, "shared-poll", 1, 0));
                ui.awaitText("pollPhase", "Poll closed"::equals);
                onFx(() -> {
                    assertEquals("1 vote counted", ui.text("pollTotal"));
                    ui.click("backToLobbyButton");
                    assertEquals("View quiz results", ui.button("openQuizButton").getText());
                    ui.click("openQuizButton");
                    assertFinalQuiz(ui);
                    ui.click("quizLobbyButton");
                    ui.click("leaveButton");
                });
                ProtocolMessage leave = read(input);
                assertEquals(LEAVE_ROOM, leave.type());
                send(peer, ok(leave, LEAVE_ROOM_RESULT, Map.of()));
                ui.awaitText("joinMessage", "You left the room."::equals);
                onFx(() -> {
                    ui.assertPage("joinView");
                    assertNull(ui.root().lookup("#joinedQuizView"));
                    ui.click("disconnectButton");
                });
                assertNull(input.readLine(), "Disconnect should close the single gameplay socket");
                listener.setSoTimeout(200);
                assertThrows(SocketTimeoutException.class, () -> {
                    try (var unexpected = listener.accept()) {
                        fail("The quiz must not establish a second connection");
                    }
                });
            }
        }
    }

    static void sessionLifecycle() throws Exception {
        var transport = new FakeTransport();
        try (var ui = Ui.open(transport)) {
            connect(ui, transport);
            onFx(() -> ui.startJoin(ROOM, "Quiz player"));
            var oldRoomEvents = transport.eventListener;
            transport.emit(event(QUESTION_STARTED, Map.of("roundId", "incomplete")));
            transport.emit(question(QUESTION_STARTED, "q1", "early", "Question before join reply"));
            onFx(() -> ui.assertPage("joinView"));
            transport.latest().respond(JOIN_ROOM_RESULT, "OK", null, Map.of("playerId", "old-player"));
            awaitQuiz(ui, "Question before join reply");
            awaitEnabled(() -> quizNode(ui, "optionA"));
            Node previousView = fxResult(() -> ui.node("joinedQuizView"));
            onFx(() -> answer(ui, "A"));
            var pendingAnswer = transport.latest();
            assertEquals(SUBMIT_ANSWER, pendingAnswer.request().type());

            transport.lose();
            onFx(() -> {
                ui.assertPage("connectionView");
                assertEquals("Connection lost", ui.text("connectionStatus"));
                assertTrue(quizNode(previousView, "optionA").isDisabled());
                assertEquals(1, transport.eventRemovals);
            });

            connect(ui, transport);
            onFx(() -> ui.startJoin(ROOM, "Quiz player"));
            transport.latest().respond(JOIN_ROOM_RESULT, "OK", null, Map.of("playerId", "new-player"));
            onFx(() -> ui.assertPage("lobbyView"));
            oldRoomEvents.accept(question(QUESTION_STARTED, "qOld", "old-connection", "Stale question"));
            pendingAnswer.respond(SUBMIT_ANSWER_RESULT, "OK", null, Map.of("accepted", true));
            onFx(() -> {
                ui.assertPage("lobbyView");
                assertEquals("new-player", ui.session().joinedRoom().orElseThrow().playerId());
            });

            transport.emit(question(BUZZER_OPENED, "q2", "other-winner", "A player from the old room won"));
            awaitQuiz(ui, "A player from the old room won");
            transport.emit(winner("q2", "other-winner", "old-player"));
            awaitValue(() -> questionStatus(ui).textProperty(), "Another player won the buzzer."::equals);
            onFx(() -> {
                assertNotSame(previousView, ui.node("joinedQuizView"));
                assertTrue(quizNode(ui, "optionA").isDisabled());
                assertTrue(quizNode(ui, "buzz").isDisabled());
            });

            transport.emit(question(BUZZER_OPENED, "q3", "ours", "The new player can win"));
            awaitQuiz(ui, "The new player can win");
            transport.emit(winner("q3", "ours", "new-player"));
            awaitEnabled(() -> quizNode(ui, "optionA"));
            transport.emit(event(ROOM_CLOSED, Map.of()));
            onFx(() -> {
                ui.assertPage("joinView");
                assertEquals("The host closed the room.", ui.text("joinMessage"));
                assertNull(ui.root().lookup("#joinedQuizView"));
                assertTrue(ui.session().joinedRoom().isEmpty());
                assertEquals(2, transport.connects);
                assertEquals(2, transport.eventRemovals);
            });
        }
        assertEquals(2, transport.lossRemovals);
    }

    private static void assertFinalQuiz(Ui ui) {
        assertEquals("Final results — saved", ((Label) quizNode(ui, "heading")).getText());
        assertEquals(150, firstScore(ui));
        assertFalse(quizNode(ui, "question").isVisible());
        assertFalse(quizNode(ui, "buzzer").isVisible());
    }

    private static void answer(Ui ui, String option) {
        ((RadioButton) quizNode(ui, "option" + option)).fire();
        ((Button) quizNode(ui, "submit")).fire();
    }

    private static void awaitQuiz(Ui ui, String questionText) throws Exception {
        awaitValue(() -> ui.node("quizLobbyButton").visibleProperty(), Boolean.TRUE::equals);
        awaitValue(() -> ((Label) quizNode(ui, "questionText")).textProperty(), questionText::equals);
    }

    private static void awaitEnabled(Supplier<Node> node) throws Exception {
        awaitValue(() -> node.get().disabledProperty(), Boolean.FALSE::equals);
    }

    private static <T> void awaitValue(Supplier<ObservableValue<T>> source, Predicate<T> ready) throws Exception {
        var result = new CompletableFuture<Void>();
        ChangeListener<T> listener = (value, previous, next) -> {
            if (ready.test(next)) {
                result.complete(null);
            }
        };
        var property = fxResult(() -> {
            var observed = source.get();
            observed.addListener(listener);
            if (ready.test(observed.getValue())) {
                result.complete(null);
            }
            return observed;
        });
        try {
            result.get(10, SECONDS);
        } finally {
            onFx(() -> property.removeListener(listener));
        }
    }

    private static Node quizNode(Ui ui, String id) {
        return quizNode(ui.node("joinedQuizView"), id);
    }

    private static Node quizNode(Node view, String id) {
        var scroll = (ScrollPane) ((BorderPane) view).getCenter();
        Node node = scroll.getContent().lookup("#" + id);
        assertNotNull(node, "Missing quiz node " + id);
        return node;
    }

    private static Label questionStatus(Ui ui) {
        return (Label) quizNode(ui, "question").lookup("#status");
    }

    private static int firstScore(Ui ui) {
        var table = (TableView<?>) quizNode(ui, "leaderboardTable");
        return assertInstanceOf(LeaderboardController.Row.class, table.getItems().getFirst()).score();
    }

    private static ProtocolMessage question(String type, String questionId, String roundId, String text) {
        return event(type, Map.of("questionId", questionId, "roundId", roundId, "text", text,
                "mode", BUZZER_OPENED.equals(type) ? "BUZZER" : "STANDARD",
                "options", Map.of("A", "TCP", "B", "UDP", "C", "TLS", "D", "QUIC"),
                "deadlineEpochMs", System.currentTimeMillis() + 30_000));
    }

    private static ProtocolMessage winner(String questionId, String roundId, String playerId) {
        return event(BUZZER_WINNER, Map.of("questionId", questionId, "roundId", roundId,
                "playerId", playerId, "deadlineEpochMs", System.currentTimeMillis() + 30_000));
    }

    private static ProtocolMessage ended(String questionId, String roundId, String correct) {
        return event(QUESTION_ENDED, Map.of("questionId", questionId, "roundId", roundId,
                "correctOptionId", correct, "reason", "ANSWERED"));
    }

    private static List<Map<String, Object>> entries(int score) {
        return List.of(Map.of("playerId", "assigned-p7", "playerName", "Quiz player", "rank", 1, "score", score));
    }

    private static ProtocolMessage ok(ProtocolMessage request, String type, Map<String, Object> payload) {
        return reply(request, type, ROOM, "OK", null, payload);
    }
}
