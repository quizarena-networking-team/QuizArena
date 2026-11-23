package com.quizarena.client.quiz;

import com.quizarena.protocol.ProtocolMessage;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in on a desktop: mvn -Dquizarena.uiTests=true test. No actual windows are opened. */
@EnabledIfSystemProperty(named = "quizarena.uiTests", matches = "true")
class QuizScreenTest {
    @BeforeAll static void startToolkit() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test void fxmlLoadsAnswersStayDisabledUntilAllowedAndStaleReplyCannotOverwriteNextRound() throws Exception {
        var client = new Client();
        var controller = new QuizScreenController[1];
        var root = new Parent[1];
        onFx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/fxml/quiz/QuizScreen.fxml"));
            root[0] = loader.load();
            new Scene(root[0]);
            root[0].applyCss();
            controller[0] = loader.getController();
            controller[0].bind(client, "ABC123", "p0");
            assertTrue(((Button) root[0].lookup("#submit")).isDisabled());
        });
        client.emit(QUESTION_STARTED, question("r1"));
        onFx(() -> {
            ((RadioButton) root[0].lookup("#optionA")).fire();
            var submit = (Button) root[0].lookup("#submit");
            assertFalse(submit.isDisabled());
            submit.fire();
            assertEquals(SUBMIT_ANSWER, client.sent.type());
            assertEquals("A", client.sent.payload().get("optionId"));
            assertTrue(submit.isDisabled());
        });
        client.emit(QUESTION_STARTED, question("r2"));
        client.pending.complete(new ProtocolMessage(SUBMIT_ANSWER_RESULT, client.sent.requestId(), "ABC123",
                "OK", null, Map.of("accepted", true)));
        onFx(() -> {
            ((RadioButton) root[0].lookup("#optionB")).fire();
            assertFalse(((Button) root[0].lookup("#submit")).isDisabled());
            assertEquals("Choose one answer.", ((Label) root[0].lookup("#status")).getText());
            controller[0].disconnected();
            assertTrue(((Button) root[0].lookup("#submit")).isDisabled());
            controller[0].close();
            assertTrue(client.unsubscribed.get());
        });
    }

    @Test void onlyBuzzerWinnerCanAnswerAndFinalResultsRender() throws Exception {
        var client = new Client();
        var controller = new QuizScreenController[1];
        var root = new Parent[1];
        onFx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/fxml/quiz/QuizScreen.fxml"));
            root[0] = loader.load(); new Scene(root[0]); root[0].applyCss();
            controller[0] = loader.getController(); controller[0].bind(client, "ABC123", "p0");
        });
        client.emit(BUZZER_OPENED, question("buzz"));
        onFx(() -> {
            assertTrue(((RadioButton) root[0].lookup("#optionA")).isDisabled());
            assertFalse(((Button) root[0].lookup("#buzz")).isDisabled());
        });
        client.emit(BUZZER_WINNER, Map.of("roundId", "buzz", "playerId", "p0", "deadlineEpochMs", System.currentTimeMillis() + 20000));
        onFx(() -> {
            assertFalse(((RadioButton) root[0].lookup("#optionA")).isDisabled());
            assertTrue(((Button) root[0].lookup("#buzz")).isDisabled());
        });
        client.emit(QUIZ_FINISHED, Map.of("persisted", true, "entries", java.util.List.of(
                Map.of("playerName", "Player", "score", 50, "rank", 1))));
        onFx(() -> {
            var table = (TableView<?>) root[0].lookup("#leaderboardTable");
            assertEquals(1, table.getItems().size());
            assertEquals(50, ((LeaderboardController.Row) table.getItems().getFirst()).score());
            assertTrue(((Button) root[0].lookup("#submit")).isDisabled());
            controller[0].close();
        });
    }

    static Map<String, Object> question(String round) {
        return Map.of("questionId", "q1", "roundId", round, "text", "Question?",
                "options", Map.of("A", "Alpha", "B", "Beta", "C", "Gamma", "D", "Delta"),
                "deadlineEpochMs", System.currentTimeMillis() + 20000);
    }

    interface FxAction { void run() throws Exception; }
    static void onFx(FxAction action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    static final class Client implements QuizClientPort {
        Consumer<ProtocolMessage> listener;
        ProtocolMessage sent;
        CompletableFuture<ProtocolMessage> pending = new CompletableFuture<>();
        AtomicBoolean unsubscribed = new AtomicBoolean();
        public CompletableFuture<ProtocolMessage> request(ProtocolMessage request) { sent = request; return pending; }
        public AutoCloseable subscribe(Consumer<ProtocolMessage> listener) {
            this.listener = listener;
            return () -> unsubscribed.set(true);
        }
        void emit(String type, Map<String, Object> payload) {
            listener.accept(new ProtocolMessage(type, null, "ABC123", null, null, payload));
        }
    }
}
