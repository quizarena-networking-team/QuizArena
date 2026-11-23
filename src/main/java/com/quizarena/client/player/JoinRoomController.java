package com.quizarena.client.player;

import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;

import java.util.function.BiConsumer;

public final class JoinRoomController {
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    @FXML private TextField roomCode;
    @FXML private TextField playerName;
    @FXML private Button joinButton;
    @FXML private ProgressIndicator joinProgress;
    @FXML private Label joinMessage;
    private BiConsumer<String, String> join;

    void bind(BiConsumer<String, String> join) {
        this.join = join;
    }

    @FXML
    private void join() {
        if (joinButton.isDisabled()) {
            return;
        }
        String room = roomCode.getText().strip();
        String name = playerName.getText().strip();
        if (room.isEmpty() || name.isEmpty()) {
            show(false, "Enter a room code and a display name.", true);
            return;
        }
        join.accept(room, name);
    }

    void show(boolean joining, String message, boolean error) {
        joinButton.setDisable(joining);
        joinButton.setText(joining ? "Joining…" : "Join room");
        roomCode.setDisable(joining);
        playerName.setDisable(joining);
        joinProgress.setManaged(joining);
        joinProgress.setVisible(joining);
        joinMessage.setText(message);
        joinMessage.setManaged(!message.isEmpty());
        joinMessage.setVisible(!message.isEmpty());
        joinMessage.pseudoClassStateChanged(ERROR, error);
    }
}
