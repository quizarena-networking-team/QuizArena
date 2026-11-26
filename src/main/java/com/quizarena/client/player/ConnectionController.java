package com.quizarena.client.player;

import com.quizarena.udp.UdpConfig;
import com.quizarena.udp.discovery.DiscoveryClient;
import com.quizarena.udp.discovery.DiscoveryClient.DiscoveredServer;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class ConnectionController {
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofMillis(1500);

    @FXML private TextField serverHost;
    @FXML private TextField serverPort;
    @FXML private Button connectButton;
    @FXML private ProgressIndicator connectProgress;
    @FXML private Label connectionMessage;
    // Member 4: UDP LAN discovery
    @FXML private Button discoverButton;
    @FXML private ProgressIndicator discoverProgress;
    @FXML private ListView<DiscoveredServer> discoveredServers;
    @FXML private Label discoveryMessage;
    private Consumer<ServerAddress> connect;
    private boolean connecting;
    private boolean searching;

    void bind(Consumer<ServerAddress> connect) {
        this.connect = connect;
    }

    @FXML
    private void initialize() {
        discoveredServers.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(DiscoveredServer server, boolean empty) {
                super.updateItem(server, empty);
                setText(empty || server == null ? null
                        : server.serverName() + "  -  " + server.host() + ":" + server.tcpPort());
            }
        });
        discoveredServers.getSelectionModel().selectedItemProperty().addListener((observable, old, server) -> {
            if (server != null && !connecting) {
                serverHost.setText(server.host());
                serverPort.setText(Integer.toString(server.tcpPort()));
            }
        });
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

    /** Broadcasts a UDP DISCOVER on a background thread; the JavaFX thread only renders the result. */
    @FXML
    private void findServers() {
        if (searching || connecting) {
            return;
        }
        int port;
        try {
            port = UdpConfig.fromEnv(System.getenv()).discoveryPort();
        } catch (IllegalArgumentException invalid) {
            showDiscovery(invalid.getMessage(), true);
            return;
        }
        searching = true;
        updateDiscoveryControls();
        discoveredServers.getItems().clear();
        showDiscovery("Searching the local network…", false);

        DiscoveryClient client = new DiscoveryClient(port, DISCOVERY_TIMEOUT);
        CompletableFuture.supplyAsync(() -> {
            try {
                return client.discover();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, runnable -> {
            Thread thread = new Thread(runnable, "quiz-lan-discovery");
            thread.setDaemon(true);
            thread.start();
        }).whenComplete((servers, failure) -> Platform.runLater(() -> showServers(servers, failure)));
    }

    private void showServers(List<DiscoveredServer> servers, Throwable failure) {
        searching = false;
        updateDiscoveryControls();
        if (failure != null) {
            showDiscovery("LAN discovery is unavailable on this network. Enter the server IP manually.", true);
        } else if (servers.isEmpty()) {
            showDiscovery("No servers found. Enter the server IP manually.", false);
        } else {
            discoveredServers.getItems().setAll(servers);
            showDiscovery(servers.size() == 1 ? "Found 1 server. Select it, then connect."
                    : "Found " + servers.size() + " servers. Select one, then connect.", false);
        }
        boolean any = !discoveredServers.getItems().isEmpty();
        discoveredServers.setManaged(any);
        discoveredServers.setVisible(any);
    }

    void show(boolean connecting, String message, boolean error) {
        this.connecting = connecting;
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
        updateDiscoveryControls();
    }

    private void updateDiscoveryControls() {
        discoverButton.setDisable(searching || connecting);
        discoveredServers.setDisable(connecting);
        discoverProgress.setManaged(searching);
        discoverProgress.setVisible(searching);
    }

    private void showDiscovery(String message, boolean error) {
        discoveryMessage.setText(message);
        discoveryMessage.setManaged(!message.isEmpty());
        discoveryMessage.setVisible(!message.isEmpty());
        discoveryMessage.pseudoClassStateChanged(ERROR, error);
    }
}
