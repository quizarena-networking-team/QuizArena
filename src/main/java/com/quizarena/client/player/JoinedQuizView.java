package com.quizarena.client.player;

import com.quizarena.client.quiz.QuizScreenController;
import com.quizarena.protocol.ProtocolMessage;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;

import java.io.IOException;

import static com.quizarena.protocol.MessageTypes.*;

/** Owns the quiz screen instance for one confirmed room membership. */
final class JoinedQuizView implements AutoCloseable {
    private final BorderPane view;
    private final QuizScreenController controller;
    private final QuizTransportAdapter adapter;
    private final ScrollPane scroll;
    private final Node question;
    private final Node buzzer;
    private boolean available;
    private boolean finished;
    private boolean closed;

    JoinedQuizView(PlayerTransport transport, PlayerSessionController.JoinedRoom room) throws IOException {
        var loader = new FXMLLoader(getClass().getResource("/fxml/quiz/QuizScreen.fxml"));
        view = loader.load();
        view.setId("joinedQuizView");
        controller = loader.getController();
        scroll = (ScrollPane) view.getCenter();
        question = scroll.getContent().lookup("#question");
        buzzer = scroll.getContent().lookup("#buzzer");
        var leaderboard = (TableView<?>) scroll.getContent().lookup("#leaderboardTable");
        leaderboard.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        adapter = new QuizTransportAdapter(transport, room.roomCode());
        controller.bind(adapter, room.roomCode(), room.playerId());
    }

    Parent view() { return view; }
    boolean isAvailable() { return available; }
    boolean isFinished() { return finished; }

    boolean receive(ProtocolMessage event) {
        if (closed || (finished && !ROOM_CLOSED.equals(event.type())) || !adapter.publish(event)) {
            return false;
        }
        switch (event.type()) {
            case QUESTION_STARTED, BUZZER_OPENED -> available = true;
            case QUIZ_FINISHED -> {
                available = true;
                finished = true;
                question.setVisible(false);
                question.setManaged(false);
                buzzer.setVisible(false);
                buzzer.setManaged(false);
            }
            default -> { }
        }
        return true;
    }

    void setInputAllowed(boolean allowed) {
        question.setDisable(!allowed);
        buzzer.setDisable(!allowed);
    }

    void revealTop() {
        scrollAfterEvent(() -> scroll.setVvalue(scroll.getVmin()));
    }

    void revealBuzzer() {
        // The controller and layout need to apply the new round before its card can be positioned.
        scrollAfterEvent(() -> {
            Parent shell = view.getScene().getRoot();
            shell.applyCss();
            shell.layout();
            var content = scroll.getContent().getLayoutBounds();
            double scrollable = content.getHeight() - scroll.getViewportBounds().getHeight();
            double offset = buzzer.getBoundsInParent().getMinY() - content.getMinY() - 8;
            scroll.setVvalue(scrollable > 0 ? Math.clamp(offset / scrollable, 0, 1) : 0);
        });
    }

    private void scrollAfterEvent(Runnable scrollAction) {
        Platform.runLater(() -> {
            if (!closed && view.getScene() != null) {
                scrollAction.run();
            }
        });
    }

    void disconnected() {
        controller.disconnected();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            controller.close();
            adapter.close();
        }
    }
}
