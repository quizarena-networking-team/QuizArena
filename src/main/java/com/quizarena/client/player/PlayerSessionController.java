package com.quizarena.client.player;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.protocol.ProtocolMessage;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static com.quizarena.client.player.PollController.VoteState.*;
import static com.quizarena.protocol.MessageTypes.*;

/** JavaFX-thread owner of the transport, navigation, and current room membership. */
public final class PlayerSessionController implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(PlayerSessionController.class.getName());
    private static final int JOIN_EVENT_LIMIT = 256;
    private static final Set<String> ROOM_EVENTS = Set.of(LOBBY_UPDATED, POLL_STARTED, POLL_RESULTS, POLL_CLOSED, ROOM_CLOSED);
    private static final PseudoClass ONLINE = PseudoClass.getPseudoClass("online");
    private static final PseudoClass BUSY = PseudoClass.getPseudoClass("busy");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private enum State { DISCONNECTED, CONNECTING, CONNECTED, JOINING, JOINED, LEAVING, LOST }

    /** Identity assigned by the server after JOIN_ROOM succeeds. */
    public record JoinedRoom(String roomCode, String playerId, String playerName) {}

    @FXML private ScrollPane playerViewport;
    @FXML private StackPane playerPages;
    @FXML private Label connectionStatus;
    @FXML private Button disconnectButton;
    @FXML private Parent connectionView;
    @FXML private Parent joinView;
    @FXML private Parent lobbyView;
    @FXML private Parent pollView;
    @FXML private ConnectionController connectionViewController;
    @FXML private JoinRoomController joinViewController;
    @FXML private LobbyController lobbyViewController;
    @FXML private PollController pollViewController;

    private final Queue<ProtocolMessage> joiningEvents = new ArrayDeque<>();
    private PlayerTransport transport;
    private State state = State.DISCONNECTED;
    private JoinedRoom joined;
    private AutoCloseable lossSubscription;
    private AutoCloseable roomSubscription;
    private long connectionGeneration;
    private long roomGeneration;
    private long nextRequestId;
    private boolean closed;

    public void bind(TcpQuizClient client) {
        bind(new PlayerTransport.Tcp(Objects.requireNonNull(client, "client")));
    }

    void bind(PlayerTransport transport) {
        if (closed || this.transport != null) {
            throw new IllegalStateException("Player session is already bound or closed");
        }
        this.transport = Objects.requireNonNull(transport, "transport");
        connectionViewController.bind(this::connect);
        joinViewController.bind(this::join);
        lobbyViewController.bind(this::leave, this::openPoll);
        pollViewController.bind(this::vote, this::backToLobby);
        changeState(State.DISCONNECTED);
        show(connectionView);
    }

    public Optional<JoinedRoom> joinedRoom() {
        return Optional.ofNullable(joined);
    }

    private void connect(ServerAddress address) {
        if (closed || (state != State.DISCONNECTED && state != State.LOST)) {
            return;
        }
        long generation = ++connectionGeneration;
        changeState(State.CONNECTING);
        connectionViewController.show(true, "Opening a gameplay connection…", false);
        lossSubscription = transport.onConnectionLost(cause -> Platform.runLater(() -> {
            if (generation == connectionGeneration) {
                resetConnection(State.LOST, "Connection to the server was lost. Connect again to rejoin.");
            }
        }));
        onFx(transport.connect(address.host(), address.port()), (ignored, failure) -> {
            if (generation != connectionGeneration) {
                return;
            }
            if (failure != null) {
                resetConnection(State.DISCONNECTED, connectError(failure));
            } else if (!transport.isConnected()) {
                resetConnection(State.LOST, "The server closed the connection. Connect again to retry.");
            } else {
                connectionViewController.show(false, "", false);
                returnToJoin("", false);
            }
        });
    }

    private void join(String roomCode, String playerName) {
        if (state != State.CONNECTED || closed) {
            return;
        }
        if (!transport.isConnected()) {
            resetConnection(State.LOST, "Connection to the server was lost. Connect again to rejoin.");
            return;
        }
        changeState(State.JOINING);
        joinViewController.show(true, "Waiting for the server to confirm room membership…", false);
        long generation = ++roomGeneration;
        roomSubscription = transport.subscribe(event -> {
            if (ROOM_EVENTS.contains(event.type()) && event.roomCode() != null && !event.roomCode().isBlank()) {
                Platform.runLater(() -> receive(generation, event));
            }
        });
        onFx(request(JOIN_ROOM, roomCode, Map.of("playerName", playerName)), (reply, failure) -> {
            if (generation != roomGeneration) {
                return;
            }
            if (failure != null || !isReply(reply, JOIN_ROOM_RESULT)) {
                resetConnection(State.DISCONNECTED, "Room membership could not be confirmed. Reconnect to try again.");
                return;
            }
            if ("ERROR".equals(reply.status())) {
                returnToJoin(serverError(reply), true);
                return;
            }
            if (!(reply.payload().get("playerId") instanceof String playerId) || playerId.isBlank()) {
                resetConnection(State.DISCONNECTED, "The server did not assign a player ID. Reconnect to try again.");
                return;
            }

            String assignedRoom = reply.roomCode() == null || reply.roomCode().isBlank() ? roomCode : reply.roomCode();
            joined = new JoinedRoom(assignedRoom, playerId, playerName);
            joinViewController.show(false, "", false);
            lobbyViewController.showRoom(assignedRoom, playerName);
            changeState(State.JOINED);
            show(lobbyView);
            while (generation == roomGeneration && !joiningEvents.isEmpty()) {
                receive(generation, joiningEvents.remove());
            }
        });
    }

    private void receive(long generation, ProtocolMessage event) {
        if (generation != roomGeneration) {
            return;
        }
        if (state == State.JOINING) {
            if (joiningEvents.size() == JOIN_EVENT_LIMIT) {
                resetConnection(State.DISCONNECTED, "Too many updates arrived while joining. Reconnect to try again.");
            } else {
                joiningEvents.add(event);
            }
            return;
        }
        if (joined == null || !joined.roomCode().equals(event.roomCode())) {
            return;
        }

        switch (event.type()) {
            case LOBBY_UPDATED -> LobbyRoster.read(event.payload()).ifPresent(lobbyViewController::showRoster);
            case POLL_STARTED -> {
                if (pollViewController.start(event.payload())) {
                    lobbyViewController.showPoll(true, false);
                    openPoll();
                }
            }
            case POLL_RESULTS -> pollViewController.update(event.payload(), false);
            case POLL_CLOSED -> {
                if (pollViewController.update(event.payload(), true)) {
                    lobbyViewController.showPoll(true, true);
                }
            }
            case ROOM_CLOSED -> returnToJoin("The host closed the room.", true);
            default -> { }
        }
    }

    private void leave() {
        if (state != State.JOINED || joined == null) {
            return;
        }
        long generation = roomGeneration;
        changeState(State.LEAVING);
        lobbyViewController.message("Waiting for the server to confirm you left the room…", false);
        onFx(request(LEAVE_ROOM, joined.roomCode(), Map.of()), (reply, failure) -> {
            if (generation != roomGeneration) {
                return;
            }
            if (failure != null || !isReply(reply, LEAVE_ROOM_RESULT)) {
                resetConnection(State.DISCONNECTED, "Leaving the room could not be confirmed. Reconnect to join again.");
            } else if ("OK".equals(reply.status())) {
                returnToJoin("You left the room.", false);
            } else if (ROOM_CLOSED.equals(reply.errorCode()) || "ROOM_NOT_FOUND".equals(reply.errorCode())) {
                returnToJoin(serverError(reply), true);
            } else {
                changeState(State.JOINED);
                lobbyViewController.message(serverError(reply), true);
            }
        });
    }

    private void vote(String optionId) {
        if (state != State.JOINED || joined == null || pollViewController.pollId() == null || pollViewController.isClosed()) {
            return;
        }
        long generation = roomGeneration;
        String pollId = pollViewController.pollId();
        pollViewController.setVoteState(SENDING, "Waiting for the server to confirm your vote…", false);
        onFx(request(SUBMIT_VOTE, joined.roomCode(), Map.of("pollId", pollId, "optionId", optionId)), (reply, failure) -> {
            if (generation == roomGeneration && pollId.equals(pollViewController.pollId())) {
                completeVote(pollId, optionId, reply, failure);
            }
        });
    }

    private void completeVote(String pollId, String optionId, ProtocolMessage reply, Throwable failure) {
        if (failure != null || !isReply(reply, SUBMIT_VOTE_RESULT)) {
            uncertainVote();
        } else if ("OK".equals(reply.status())) {
            if (Boolean.TRUE.equals(reply.payload().get("accepted")) && pollId.equals(reply.payload().get("pollId"))
                    && optionId.equals(reply.payload().get("optionId"))) {
                pollViewController.setVoteState(LOCKED, "Vote accepted.", false);
            } else {
                uncertainVote();
            }
        } else if (ROOM_CLOSED.equals(reply.errorCode())) {
            returnToJoin(serverError(reply), true);
        } else if ("POLL_CLOSED".equals(reply.errorCode())) {
            pollViewController.closeVoting();
            pollViewController.setVoteState(LOCKED, "Voting has ended.", true);
            lobbyViewController.showPoll(true, true);
        } else if ("BAD_REQUEST".equals(reply.errorCode()) && !pollViewController.isClosed()) {
            pollViewController.setVoteState(READY, "The server rejected that choice. Select an option and try again.", true);
        } else if ("INTERNAL_ERROR".equals(reply.errorCode())) {
            uncertainVote();
        } else {
            pollViewController.setVoteState(LOCKED, serverError(reply), true);
        }
    }

    private void uncertainVote() {
        pollViewController.setVoteState(LOCKED, "Vote delivery is unconfirmed. Keep watching the server's results.", true);
    }

    private void openPoll() {
        if (state == State.JOINED && pollViewController.pollId() != null) {
            show(pollView);
        }
    }

    private void backToLobby() {
        if (state == State.JOINED) {
            show(lobbyView);
        }
    }

    @FXML
    private void disconnect() {
        if (transport != null && !closed) {
            resetConnection(State.DISCONNECTED, state == State.CONNECTING ? "Connection cancelled." : "Disconnected from the server.", false);
        }
    }

    private void resetConnection(State next, String message) {
        resetConnection(next, message, true);
    }

    private void resetConnection(State next, String message, boolean error) {
        connectionGeneration++;
        unsubscribe(lossSubscription);
        lossSubscription = null;
        clearRoom();
        transport.disconnect();
        changeState(next);
        joinViewController.show(false, "", false);
        connectionViewController.show(false, message, error);
        show(connectionView);
    }

    private void returnToJoin(String message, boolean error) {
        if (!transport.isConnected()) {
            resetConnection(State.LOST, "Connection to the server was lost. Connect again to rejoin.");
            return;
        }
        clearRoom();
        changeState(State.CONNECTED);
        joinViewController.show(false, message, error);
        show(joinView);
    }

    private void clearRoom() {
        roomGeneration++;
        unsubscribe(roomSubscription);
        roomSubscription = null;
        joined = null;
        joiningEvents.clear();
        lobbyViewController.reset();
        pollViewController.clear();
    }

    private void changeState(State next) {
        state = next;
        connectionStatus.setText(switch (next) {
            case DISCONNECTED -> "Disconnected";
            case CONNECTING -> "Connecting";
            case CONNECTED, JOINED -> "Connected";
            case JOINING -> "Joining room";
            case LEAVING -> "Leaving room";
            case LOST -> "Connection lost";
        });
        connectionStatus.pseudoClassStateChanged(ONLINE, next == State.CONNECTED || next == State.JOINED);
        connectionStatus.pseudoClassStateChanged(BUSY, next == State.CONNECTING || next == State.JOINING || next == State.LEAVING);
        connectionStatus.pseudoClassStateChanged(ERROR, next == State.LOST);
        boolean active = next != State.DISCONNECTED && next != State.LOST;
        disconnectButton.setManaged(active);
        disconnectButton.setVisible(active);
        disconnectButton.setText(next == State.CONNECTING ? "Cancel" : "Disconnect");
        lobbyViewController.setRoomActions(next == State.JOINED, next == State.LEAVING);
        pollViewController.setRoomActions(next == State.JOINED);
    }

    private void show(Parent page) {
        for (Node view : playerPages.getChildren()) {
            view.setManaged(view == page);
            view.setVisible(view == page);
        }
        playerViewport.setVvalue(0);
    }

    private CompletableFuture<ProtocolMessage> request(String type, String room, Map<String, Object> payload) {
        // Correlation IDs need to be unique on this connection. A sequence avoids waiting for entropy on the FX thread.
        String requestId = "player-" + ++nextRequestId;
        return transport.request(new ProtocolMessage(type, requestId, room, null, null, payload));
    }

    private static boolean isReply(ProtocolMessage reply, String type) {
        return reply != null && type.equals(reply.type()) && ("OK".equals(reply.status()) || "ERROR".equals(reply.status()));
    }

    private static String serverError(ProtocolMessage reply) {
        String code = Objects.toString(reply.errorCode(), "");
        return switch (code) {
            case "ROOM_NOT_FOUND" -> "Room not found. Check the room code with your host.";
            case ROOM_CLOSED -> "The host closed the room.";
            case "INVALID_PLAYER" -> "The server no longer recognizes this player session.";
            case "ALREADY_VOTED" -> "The server already recorded a vote for this player.";
            case "BAD_REQUEST" -> "The server rejected the request. Check the entered details.";
            case "UNAUTHORIZED" -> "The server denied this action.";
            case "INTERNAL_ERROR" -> "The server could not complete the request.";
            default -> "The server rejected the request.";
        };
    }

    private static String connectError(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof UnknownHostException) {
            return "Server address not found. Check the hostname or IP address.";
        }
        if (cause instanceof ConnectException) {
            return "Connection refused. Check that the server is running at this address and port.";
        }
        if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
            return "Connection timed out. Check the address, port, and network connection.";
        }
        return "Could not connect. Check the server address, port, and network connection.";
    }

    private static <T> void onFx(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
        future.whenComplete((result, failure) -> Platform.runLater(() -> callback.accept(result, failure)));
    }

    private static void unsubscribe(AutoCloseable subscription) {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception failure) {
                LOG.log(System.Logger.Level.WARNING, "Could not remove player subscription", failure);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connectionGeneration++;
        unsubscribe(lossSubscription);
        lossSubscription = null;
        if (transport != null) {
            clearRoom();
            transport.close();
        }
    }
}
