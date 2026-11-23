package com.quizarena.client.quiz;

import com.quizarena.protocol.ProtocolMessage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.util.Duration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.quizarena.protocol.MessageTypes.SUBMIT_ANSWER;

public final class QuestionController implements AutoCloseable {
    @FXML private Label questionText;
    @FXML private Label countdown;
    @FXML private Label status;
    @FXML private RadioButton optionA;
    @FXML private RadioButton optionB;
    @FXML private RadioButton optionC;
    @FXML private RadioButton optionD;
    @FXML private Button submit;
    private final ToggleGroup choices = new ToggleGroup();
    private List<RadioButton> options;
    private Timeline timer;
    private QuizClientPort client;
    private String roomCode;
    private String questionId;
    private String roundId;
    private long deadline;
    private boolean answeringAllowed;
    private boolean connected;
    private boolean submitted;
    private boolean pending;

    @FXML private void initialize() {
        options = List.of(optionA, optionB, optionC, optionD);
        for (int i = 0; i < options.size(); i++) {
            options.get(i).setToggleGroup(choices);
            options.get(i).setUserData(String.valueOf((char) ('A' + i)));
        }
        choices.selectedToggleProperty().addListener((o, before, after) -> refresh());
        timer = new Timeline(new KeyFrame(Duration.millis(200), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        refresh();
    }

    void bind(QuizClientPort client, String roomCode) {
        this.client = client;
        this.roomCode = roomCode;
        connected = true;
        refresh();
    }

    void show(Map<String, Object> payload, boolean allowed) {
        questionId = (String) payload.get("questionId");
        roundId = (String) payload.get("roundId");
        questionText.setText((String) payload.get("text"));
        Map<?, ?> texts = (Map<?, ?>) payload.get("options");
        for (var option : options) option.setText(option.getUserData() + ". " + texts.get(option.getUserData()));
        deadline = ((Number) payload.get("deadlineEpochMs")).longValue();
        answeringAllowed = allowed;
        submitted = false;
        pending = false;
        choices.selectToggle(null);
        status.setText(allowed ? "Choose one answer." : "Press the buzzer to compete for this question.");
        timer.play();
        refresh();
    }

    void allowBuzzerAnswer(String id, long deadline, boolean winner) {
        if (!id.equals(roundId)) return;
        this.deadline = deadline;
        answeringAllowed = winner;
        status.setText(winner ? "You won the buzzer. Choose your answer." : "Another player won the buzzer.");
        refresh();
    }

    void end(Map<String, Object> payload) {
        if (!payload.get("roundId").equals(roundId)) return;
        answeringAllowed = false;
        pending = false;
        timer.stop();
        countdown.setText("Round ended");
        status.setText("Correct answer: " + payload.get("correctOptionId"));
        refresh();
    }

    void setConnected(boolean connected) {
        this.connected = connected;
        if (!connected) {
            answeringAllowed = false;
            status.setText("Disconnected. Rejoin through the connection screen.");
            timer.stop();
        }
        refresh();
    }

    @FXML private void submitAnswer() {
        if (!canAnswer() || choices.getSelectedToggle() == null) return;
        String requestRound = roundId;
        pending = true;
        refresh();
        status.setText("Submitting answer…");
        var request = new ProtocolMessage(SUBMIT_ANSWER, UUID.randomUUID().toString(), roomCode,
                null, null, Map.of("questionId", questionId, "roundId", roundId,
                "optionId", choices.getSelectedToggle().getUserData()));
        try {
            client.request(request).whenComplete((reply, failure) -> Platform.runLater(() -> {
                if (!requestRound.equals(roundId) || !answeringAllowed || !connected) return;
                pending = false;
                if (failure != null) {
                    // Delivery is uncertain. Do not invite a second choice for a potentially accepted answer.
                    submitted = true;
                    status.setText("Answer delivery could not be confirmed. Wait for the round results.");
                } else if ("OK".equals(reply.status())) {
                    submitted = true;
                    status.setText("Answer received. Waiting for the round to end.");
                } else {
                    status.setText("Answer rejected: " + reply.errorCode());
                    if ("QUESTION_CLOSED".equals(reply.errorCode()) || "ANSWER_ALREADY_SUBMITTED".equals(reply.errorCode())) {
                        submitted = true;
                    }
                }
                refresh();
            }));
        } catch (RuntimeException failure) {
            pending = false;
            submitted = true;
            status.setText("Answer delivery could not be confirmed. Wait for the round results.");
            refresh();
        }
    }

    private boolean canAnswer() {
        return connected && answeringAllowed && !submitted && !pending && System.currentTimeMillis() < deadline;
    }

    private void refresh() {
        boolean enabled = canAnswer();
        submit.setDisable(!enabled || choices.getSelectedToggle() == null);
        if (options != null) options.forEach(option -> option.setDisable(!enabled));
        if (answeringAllowed || (timer != null && timer.getStatus() == Timeline.Status.RUNNING)) {
            long remaining = Math.max(0, deadline - System.currentTimeMillis());
            countdown.setText("Time remaining: " + (remaining + 999) / 1000 + "s");
        }
    }

    @Override public void close() { setConnected(false); timer.stop(); }
}
