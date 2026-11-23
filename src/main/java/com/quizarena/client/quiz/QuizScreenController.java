package com.quizarena.client.quiz;

import com.quizarena.protocol.ProtocolMessage;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.util.Map;
import java.util.Objects;

import static com.quizarena.protocol.MessageTypes.*;

/** FXML entry point. Bind after JOIN_ROOM; close when leaving. One screen instance per joined session. */
public final class QuizScreenController implements AutoCloseable {
    @FXML private Label connectionStatus;
    @FXML private QuestionController questionController;
    @FXML private BuzzerController buzzerController;
    @FXML private LeaderboardController leaderboardController;
    private AutoCloseable subscription;
    private String roomCode;
    private String playerId;
    private String activeRound;
    private boolean closed;
    private boolean connected;
    private boolean roundOpen;

    public void bind(QuizClientPort client, String roomCode, String playerId) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Bind on the JavaFX thread");
        if (subscription != null || closed) throw new IllegalStateException("Screen is already bound or closed");
        this.roomCode = Objects.requireNonNull(roomCode);
        this.playerId = Objects.requireNonNull(playerId);
        questionController.bind(client, roomCode);
        buzzerController.bind(client, roomCode);
        connected = true;
        connectionStatus.setText("Room " + roomCode + " · Waiting for the host");
        subscription = client.subscribe(event -> Platform.runLater(() -> receive(event)));
    }

    /** M2 connection-state listener must call this on the JavaFX thread on disconnect. */
    public void disconnected() {
        connected = false;
        connectionStatus.setText("Disconnected — return to the connection screen to rejoin");
        questionController.setConnected(false);
        buzzerController.setConnected(false);
    }

    private void receive(ProtocolMessage event) {
        if (closed || !connected || !roomCode.equals(event.roomCode())) return;
        Map<String, Object> payload = event.payload();
        switch (event.type()) {
            case QUESTION_STARTED -> {
                activeRound = (String) payload.get("roundId");
                roundOpen = true;
                questionController.show(payload, true);
                buzzerController.stop("Buzzer is not open.");
                connectionStatus.setText("Room " + roomCode + " · Question in progress");
            }
            case BUZZER_OPENED -> {
                activeRound = (String) payload.get("roundId");
                roundOpen = true;
                questionController.show(payload, false);
                buzzerController.open(payload);
                connectionStatus.setText("Room " + roomCode + " · Buzzer round");
            }
            case BUZZER_WINNER -> {
                if (!roundOpen || !Objects.equals(activeRound, payload.get("roundId"))) return;
                boolean winner = playerId.equals(payload.get("playerId"));
                buzzerController.stop(winner ? "You won the buzzer!" : "Winner: " + payload.get("playerId"));
                questionController.allowBuzzerAnswer(activeRound,
                        ((Number) payload.get("deadlineEpochMs")).longValue(), winner);
            }
            case QUESTION_ENDED -> {
                if (!Objects.equals(activeRound, payload.get("roundId"))) return;
                roundOpen = false;
                questionController.end(payload);
                buzzerController.stop("Round ended.");
                connectionStatus.setText("Room " + roomCode + " · Waiting for the next round");
            }
            case LEADERBOARD_UPDATED, LEADERBOARD_RESULT -> leaderboardController.show(payload, false);
            case QUIZ_FINISHED -> {
                roundOpen = false;
                leaderboardController.show(payload, Boolean.TRUE.equals(payload.get("persisted")));
                questionController.setConnected(false);
                buzzerController.stop("Quiz finished.");
                connectionStatus.setText("Quiz finished · Results saved");
            }
            case ROOM_CLOSED -> disconnected();
            default -> { /* M2 routes unrelated lobby/poll messages elsewhere. */ }
        }
    }

    @Override public void close() {
        closed = true;
        connected = false;
        questionController.close();
        buzzerController.close();
        if (subscription != null) {
            try { subscription.close(); }
            catch (Exception failure) { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Could not remove quiz event subscription", failure); }
            subscription = null;
        }
    }
}
