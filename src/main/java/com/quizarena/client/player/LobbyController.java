package com.quizarena.client.player;

import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import java.util.List;

public final class LobbyController {
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    @FXML private Label lobbyRoom;
    @FXML private Label lobbyPlayer;
    @FXML private Label rosterCount;
    @FXML private ListView<String> roster;
    @FXML private Label rosterPlaceholder;
    @FXML private Button leaveButton;
    @FXML private Button openPollButton;
    @FXML private Label lobbyPollStatus;
    @FXML private Label lobbyMessage;

    private Runnable leave;
    private Runnable openPoll;
    private boolean roomActions;
    private boolean hasPoll;

    void bind(Runnable leave, Runnable openPoll) {
        this.leave = leave;
        this.openPoll = openPoll;
        roster.setPlaceholder(rosterPlaceholder);
    }

    void showRoom(String room, String player) {
        reset();
        lobbyRoom.setText(room);
        lobbyPlayer.setText(player);
    }

    void reset() {
        lobbyRoom.setText("");
        lobbyPlayer.setText("");
        roster.getItems().clear();
        rosterCount.setText("");
        rosterPlaceholder.setText("Waiting for the server's player list.");
        showPoll(false, false);
        message("", false);
    }

    void showRoster(List<String> players) {
        roster.getItems().setAll(players);
        rosterCount.setText(players.size() + (players.size() == 1 ? " player" : " players"));
        rosterPlaceholder.setText("The server reports an empty lobby.");
    }

    void showPoll(boolean available, boolean closed) {
        hasPoll = available;
        lobbyPollStatus.setText(!available ? "A live poll will appear here when the host starts one."
                : closed ? "The host closed the poll. Final totals are available." : "The host opened a live poll.");
        openPollButton.setText(closed ? "View results" : "Open live poll");
        openPollButton.setManaged(available);
        openPollButton.setVisible(available);
        openPollButton.setDisable(!available || !roomActions);
    }

    void setRoomActions(boolean enabled, boolean leaving) {
        roomActions = enabled;
        leaveButton.setDisable(!enabled);
        leaveButton.setText(leaving ? "Leaving…" : "Leave room");
        openPollButton.setDisable(!enabled || !hasPoll);
    }

    void message(String message, boolean error) {
        lobbyMessage.setText(message);
        lobbyMessage.setManaged(!message.isEmpty());
        lobbyMessage.setVisible(!message.isEmpty());
        lobbyMessage.pseudoClassStateChanged(ERROR, error);
    }

    @FXML private void leave() { leave.run(); }
    @FXML private void openPoll() { openPoll.run(); }
}
