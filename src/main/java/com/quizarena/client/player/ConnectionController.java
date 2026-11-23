package com.quizarena.client.player;

import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;

import java.util.function.Consumer;

public final class ConnectionController {
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    @FXML private TextField serverHost;
    @FXML private TextField serverPort;
    @FXML private Button connectButton;
    @FXML private ProgressIndicator connectProgress;
    @FXML private Label connectionMessage;
    private Consumer<ServerAddress> connect;

    void bind(Consumer<ServerAddress> connect) {
        this.connect = connect;
    }

    @FXML
    private void connect() {
        if (connectButton.isDisabled()) {
            return;
        }
        ServerAddress address;
        try {
            address = ServerAddress.parse(serverHost.getText(), serverPort.getText());
        } catch (IllegalArgumentException failure) {
            show(false, failure.getMessage(), true);
            return;
        }
        connect.accept(address);
    }

    void show(boolean connecting, String message, boolean error) {
        connectButton.setDisable(connecting);
        connectButton.setText(connecting ? "Connecting…" : "Connect");
        serverHost.setDisable(connecting);
        serverPort.setDisable(connecting);
        connectProgress.setManaged(connecting);
        connectProgress.setVisible(connecting);
        connectionMessage.setText(message);
        connectionMessage.setManaged(!message.isEmpty());
        connectionMessage.setVisible(!message.isEmpty());
        connectionMessage.pseudoClassStateChanged(ERROR, error);
    }
}
