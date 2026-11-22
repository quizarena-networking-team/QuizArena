package com.quizarena.client.quiz;

import com.quizarena.protocol.ProtocolMessage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.Map;
import java.util.UUID;

import static com.quizarena.protocol.MessageTypes.PRESS_BUZZER;

public final class BuzzerController implements AutoCloseable {
    @FXML private Button buzz;
    @FXML private Label status;
    private QuizClientPort client;
    private String roomCode;
    private String roundId;
    private String questionId;
    private long deadline;
    private boolean open;
    private boolean connected;
    private Timeline timer;

    @FXML private void initialize() {
        timer = new Timeline(new KeyFrame(Duration.millis(200), event -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
    }

    void bind(QuizClientPort client, String roomCode) {
        this.client = client;
        this.roomCode = roomCode;
        connected = true;
    }

    void open(Map<String, Object> payload) {
        questionId = (String) payload.get("questionId");
        roundId = (String) payload.get("roundId");
        deadline = ((Number) payload.get("deadlineEpochMs")).longValue();
        open = true;
        status.setText("Buzzer is open. First valid request accepted by the server wins.");
        timer.play();
        refresh();
    }

    void stop(String message) {
        open = false;
        status.setText(message);
        timer.stop();
        refresh();
    }

    void setConnected(boolean connected) {
        this.connected = connected;
        if (!connected) stop("Disconnected.");
        refresh();
    }

    @FXML private void press() {
        if (!connected || !open || System.currentTimeMillis() >= deadline) return;
        open = false;
        String requestRound = roundId;
        refresh();
        status.setText("Buzzer request sent…");
        try {
            client.request(new ProtocolMessage(PRESS_BUZZER, UUID.randomUUID().toString(), roomCode,
                    null, null, Map.of("questionId", questionId, "roundId", roundId)))
                    .whenComplete((reply, failure) -> Platform.runLater(() -> {
                        if (!requestRound.equals(roundId) || !connected || !timer.getStatus().equals(Timeline.Status.RUNNING)) return;
                        if (failure != null) stop("Buzzer delivery could not be confirmed.");
                        else if (!"OK".equals(reply.status())) stop("Buzzer rejected: " + reply.errorCode());
                        // The authoritative BUZZER_WINNER event controls who can answer.
                    }));
        } catch (RuntimeException failure) { stop("Buzzer delivery could not be confirmed."); }
    }

    private void refresh() { buzz.setDisable(!connected || !open || System.currentTimeMillis() >= deadline); }
    @Override public void close() { setConnected(false); timer.stop(); }
}
