package com.quizarena.client.player;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.quizarena.protocol.MessageTypes.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class PlayerUiScenarios {
    static final String ROOM = "ABC123";
    static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private PlayerUiScenarios() {}

    static void connect() throws Exception {
        var transport = new FakeTransport();
        try (var ui = Ui.open(transport)) {
            onFx(() -> {
                ui.assertPage("connectionView");
                assertFalse(ui.root().getStylesheets().isEmpty());
                assertEquals("5000", ui.input("serverPort").getText());
                ui.input("serverHost").setText("http://localhost");
                ui.click("connectButton");
                assertTrue(ui.text("connectionMessage").contains("valid hostname"));
                ui.input("serverHost").setText("localhost");
                ui.input("serverPort").setText("65536");
                ui.click("connectButton");
                assertTrue(ui.text("connectionMessage").contains("65535"));
                assertEquals(0, transport.connects);

                ui.startConnection("localhost", 5001);
                assertEquals("Connecting", ui.text("connectionStatus"));
                assertTrue(ui.node("connectProgress").isVisible());
                assertTrue(ui.button("connectButton").isDisabled());
                ui.click("connectButton");
                assertEquals(1, transport.connects);
                assertEquals(new ServerAddress("localhost", 5001), transport.address);
            });
            var abandoned = transport.connecting;
            onFx(() -> ui.click("disconnectButton"));
            abandoned.complete(null);
            onFx(() -> {
                ui.assertPage("connectionView");
                assertEquals("Disconnected", ui.text("connectionStatus"));
                assertTrue(ui.text("connectionMessage").contains("cancelled"));
                ui.startConnection("localhost", 5001);
            });
            transport.connecting.completeExceptionally(new SocketTimeoutException("Test connection timed out"));
            onFx(() -> {
                ui.assertPage("connectionView");
                assertTrue(ui.text("connectionMessage").contains("Connection timed out"));
                ui.startConnection("localhost", 5001);
            });
            transport.completeConnect();
            onFx(() -> {
                ui.assertPage("joinView");
                assertEquals("Connected", ui.text("connectionStatus"));
                assertFalse(ui.node("connectProgress").isVisible());
                assertTrue(ui.session().joinedRoom().isEmpty());
            });
        }
        assertTrue(transport.closed);
        assertEquals(3, transport.lossRemovals);
    }

    static void refused() throws Exception {
        try (var unused = new Socket(); var ui = Ui.open(new PlayerTransport.Tcp(new TcpQuizClient()))) {
            unused.bind(new InetSocketAddress(LOOPBACK, 0));
            onFx(() -> ui.startConnection(LOOPBACK.getHostAddress(), unused.getLocalPort()));
            ui.awaitText("connectionMessage", value -> value.contains("Connection refused"));
            onFx(() -> {
                ui.assertPage("connectionView");
                assertEquals("Disconnected", ui.text("connectionStatus"));
                assertFalse(ui.button("connectButton").isDisabled());
                assertFalse(ui.node("connectProgress").isVisible());
            });
        }
    }

    static void join() throws Exception {
        var transport = new FakeTransport();
        try (var ui = Ui.open(transport)) {
            connect(ui, transport);
            onFx(() -> {
                ui.click("joinButton");
                assertEquals(0, transport.requests.size());
                assertTrue(ui.text("joinMessage").contains("room code"));
                ui.startJoin("bad123", "  Amal  ");
                ui.assertPage("joinView");
                assertEquals("Joining room", ui.text("connectionStatus"));
                assertTrue(ui.node("joinProgress").isVisible());
                assertTrue(ui.button("joinButton").isDisabled());
                assertTrue(ui.session().joinedRoom().isEmpty());
            });
            Pending rejected = transport.latest();
            assertEquals(JOIN_ROOM, rejected.request().type());
            assertEquals("bad123", rejected.request().roomCode());
            assertEquals(Map.of("playerName", "Amal"), rejected.request().payload());
            rejected.respond(JOIN_ROOM_RESULT, "ERROR", "ROOM_NOT_FOUND", Map.of());
            onFx(() -> {
                ui.assertPage("joinView");
                assertTrue(ui.text("joinMessage").contains("Room not found"));
                assertFalse(ui.button("joinButton").isDisabled());
                assertEquals(1, transport.eventRemovals);
                ui.startJoin("abc123", "Amal");
            });

            transport.emit(new ProtocolMessage(LOBBY_UPDATED, null, "OTHER", null, null, roster("Unrelated")));
            transport.emit(event(LOBBY_UPDATED, roster("Amal", "Nimal")));
            onFx(() -> ui.assertPage("joinView"));
            transport.latest().respond(JOIN_ROOM_RESULT, ROOM, "OK", null, Map.of("playerId", "server-p9"));
            onFx(() -> {
                ui.assertPage("lobbyView");
                assertEquals(new PlayerSessionController.JoinedRoom(ROOM, "server-p9", "Amal"), ui.session().joinedRoom().orElseThrow());
                assertEquals(ROOM, ui.text("lobbyRoom"));
                assertEquals("Amal", ui.text("lobbyPlayer"));
                assertEquals(List.of("Amal", "Nimal"), ui.roster());
                assertEquals(1, transport.connects);
            });
            transport.emit(event(LOBBY_UPDATED, Map.of("players", List.of(Map.of("playerName", 12)))));
            onFx(() -> assertEquals(List.of("Amal", "Nimal"), ui.roster()));
            transport.emit(event(LOBBY_UPDATED, roster("Amal")));
            onFx(() -> assertEquals(List.of("Amal"), ui.roster()));
            transport.emit(event(ROOM_CLOSED, Map.of()));
            onFx(() -> {
                ui.assertPage("joinView");
                assertTrue(ui.text("joinMessage").contains("closed"));
                assertTrue(ui.session().joinedRoom().isEmpty());
                assertTrue(ui.roster().isEmpty());
                assertEquals(2, transport.eventRemovals);
                ui.startJoin(ROOM, "Amal");
            });
            transport.latest().respond(JOIN_ROOM_RESULT, "OK", null, Map.of());
            onFx(() -> {
                ui.assertPage("connectionView");
                assertTrue(ui.text("connectionMessage").contains("player ID"));
                assertTrue(ui.session().joinedRoom().isEmpty());
                assertEquals(3, transport.eventRemovals);
            });
        }
    }

    static void poll() throws Exception {
        var transport = new FakeTransport();
        try (var ui = Ui.open(transport)) {
            connect(ui, transport);
            join(ui, transport);
            transport.emit(event(POLL_STARTED, Map.of("pollId", "malformed", "question", 28)));
            onFx(() -> ui.assertPage("lobbyView"));
            transport.emit(pollStarted("poll-1", "Which transport?"));
            onFx(() -> {
                ui.assertPage("pollView");
                assertEquals("Which transport?", ui.text("pollQuestion"));
                assertEquals("0 votes counted", ui.text("pollTotal"));
                assertEquals("UDP", ui.option("B").getText());
                assertTrue(ui.button("submitVoteButton").isDisabled());
                ui.submit("B");
                assertEquals("Submitting…", ui.button("submitVoteButton").getText());
                assertTrue(ui.option("A").isDisabled());
                ui.click("submitVoteButton");
                assertEquals(2, transport.requests.size());
            });
            Pending vote = transport.latest();
            assertEquals(SUBMIT_VOTE, vote.request().type());
            assertEquals(ROOM, vote.request().roomCode());
            assertEquals(Map.of("pollId", "poll-1", "optionId", "B"), vote.request().payload());
            transport.emit(counts(POLL_RESULTS, "poll-1", 2, 1));
            transport.emit(counts(POLL_RESULTS, "stale-poll", 90, 90));
            transport.emit(event(POLL_RESULTS, Map.of("pollId", "poll-1", "counts", Map.of("A", -1, "B", 18))));
            onFx(() -> {
                assertEquals("3 votes counted", ui.text("pollTotal"));
                assertEquals(List.of("1 vote", "2 votes"), ui.optionCounts());
            });
            vote.acceptVote();
            onFx(() -> {
                assertEquals("Vote accepted.", ui.text("voteMessage"));
                assertEquals("3 votes counted", ui.text("pollTotal"));
                assertTrue(ui.button("submitVoteButton").isDisabled());
            });
            transport.emit(event(POLL_CLOSED, Map.of("pollId", "poll-1")));
            onFx(() -> {
                assertEquals("Poll closed", ui.text("pollPhase"));
                assertEquals("3 votes counted", ui.text("pollTotal"));
            });
            transport.emit(counts(POLL_CLOSED, "poll-1", 2, 3));
            transport.emit(counts(POLL_RESULTS, "poll-1", 9, 9));
            onFx(() -> {
                assertEquals("5 votes counted", ui.text("pollTotal"));
                assertTrue(ui.option("A").isDisabled());
                ui.click("backToLobbyButton");
                ui.assertPage("lobbyView");
                assertEquals("View results", ui.button("openPollButton").getText());
                ui.click("openPollButton");
                ui.assertPage("pollView");
                assertEquals(0, transport.eventRemovals);
            });
        }
        assertEquals(1, transport.eventRemovals);
    }

    static void stale() throws Exception {
        var transport = new FakeTransport();
        try (var ui = Ui.open(transport)) {
            connect(ui, transport);
            join(ui, transport);
            var oldListener = transport.eventListener;
            transport.emit(pollStarted("old", "First poll"));
            onFx(() -> ui.submit("A"));
            var oldVote = transport.latest();
            transport.emit(pollStarted("second", "Second poll"));
            oldVote.acceptVote();
            onFx(() -> {
                assertEquals("Second poll", ui.text("pollQuestion"));
                assertEquals("Choose an option to vote.", ui.text("voteMessage"));
                ui.submit("B");
            });
            transport.latest().response().completeExceptionally(new TimeoutException("Test request timed out"));
            transport.emit(pollStarted("second", "Duplicate start event"));
            onFx(() -> {
                assertTrue(ui.text("voteMessage").contains("unconfirmed"));
                assertTrue(ui.button("submitVoteButton").isDisabled());
                assertEquals("Second poll", ui.text("pollQuestion"));
            });

            transport.emit(pollStarted("third", "Third poll"));
            onFx(() -> ui.submit("A"));
            transport.latest().respond(SUBMIT_VOTE_RESULT, "ERROR", "BAD_REQUEST", Map.of());
            onFx(() -> {
                assertFalse(ui.option("B").isDisabled());
                ui.submit("B");
                ui.click("backToLobbyButton");
                ui.click("leaveButton");
                assertEquals("Leaving room", ui.text("connectionStatus"));
                assertTrue(ui.button("leaveButton").isDisabled());
            });
            Pending leaving = transport.latest();
            Pending outstanding = transport.requests.get(transport.requests.size() - 2);
            assertEquals(LEAVE_ROOM, leaving.request().type());
            assertEquals(Map.of(), leaving.request().payload());
            leaving.respond(LEAVE_ROOM_RESULT, "OK", null, Map.of());
            onFx(() -> {
                ui.assertPage("joinView");
                assertEquals(1, transport.eventRemovals);
                assertTrue(ui.session().joinedRoom().isEmpty());
            });
            join(ui, transport);
            oldListener.accept(pollStarted("ghost", "Previous membership"));
            outstanding.acceptVote();
            onFx(() -> {
                ui.assertPage("lobbyView");
                assertFalse(ui.node("openPollButton").isVisible());
                assertEquals(1, transport.connects);
            });
        }
    }

    static void loss() throws Exception {
        var transport = new FakeTransport();
        try (var ui = Ui.open(transport)) {
            connect(ui, transport);
            var oldLoss = transport.lossListener;
            onFx(() -> ui.startJoin(ROOM, "Amal"));
            var oldJoin = transport.latest();
            transport.lose();
            onFx(() -> {
                ui.assertPage("connectionView");
                assertEquals("Connection lost", ui.text("connectionStatus"));
                assertEquals(1, transport.eventRemovals);
                assertEquals(1, transport.lossRemovals);
                assertTrue(ui.session().joinedRoom().isEmpty());
            });

            connect(ui, transport);
            onFx(() -> ui.startJoin(ROOM, "Amal"));
            oldJoin.respond(JOIN_ROOM_RESULT, "OK", null, Map.of("playerId", "stale-id"));
            oldLoss.accept(new IOException("Former connection"));
            onFx(() -> {
                ui.assertPage("joinView");
                assertEquals("Joining room", ui.text("connectionStatus"));
                assertTrue(ui.session().joinedRoom().isEmpty());
            });
            transport.latest().respond(JOIN_ROOM_RESULT, "OK", null, Map.of("playerId", "new-id"));
            onFx(() -> {
                ui.assertPage("lobbyView");
                assertEquals("new-id", ui.session().joinedRoom().orElseThrow().playerId());
            });
        }
        assertTrue(transport.closed);
        assertEquals(2, transport.eventRemovals);
        assertEquals(2, transport.lossRemovals);
    }

    static void loopback() throws Exception {
        try (var listener = new ServerSocket(0, 1, LOOPBACK);
             var ui = Ui.open(new PlayerTransport.Tcp(new TcpQuizClient()))) {
            listener.setSoTimeout(6_000);
            onFx(() -> ui.startConnection(LOOPBACK.getHostAddress(), listener.getLocalPort()));
            try (var peer = listener.accept()) {
                peer.setSoTimeout(6_000);
                var input = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                ui.awaitText("connectionStatus", "Connected"::equals);
                onFx(() -> ui.startJoin(ROOM, "Loopback player"));
                ProtocolMessage join = read(input);
                assertEquals(JOIN_ROOM, join.type());
                assertEquals(Map.of("playerName", "Loopback player"), join.payload());
                send(peer, event(LOBBY_UPDATED, roster("Loopback player")));
                send(peer, reply(join, JOIN_ROOM_RESULT, ROOM, "OK", null, Map.of("playerId", "wire-assigned")));
                ui.awaitText("lobbyRoom", ROOM::equals);
                send(peer, pollStarted("wire-poll", "TCP poll"));
                ui.awaitText("pollQuestion", "TCP poll"::equals);
                onFx(() -> {
                    ui.assertPage("pollView");
                    assertEquals("wire-assigned", ui.session().joinedRoom().orElseThrow().playerId());
                    ui.submit("A");
                });
                ProtocolMessage vote = read(input);
                assertEquals(SUBMIT_VOTE, vote.type());
                assertNotEquals(join.requestId(), vote.requestId());
                assertEquals(Map.of("pollId", "wire-poll", "optionId", "A"), vote.payload());
                send(peer, counts(POLL_RESULTS, "wire-poll", 1, 0));
                send(peer, reply(vote, SUBMIT_VOTE_RESULT, ROOM, "OK", null,
                        Map.of("pollId", "wire-poll", "optionId", "A", "accepted", true)));
                ui.awaitText("voteMessage", "Vote accepted."::equals);
                ui.awaitText("pollTotal", "1 vote counted"::equals);
                send(peer, counts(POLL_CLOSED, "wire-poll", 1, 0));
                ui.awaitText("pollPhase", "Poll closed"::equals);
                onFx(() -> {
                    ui.click("backToLobbyButton");
                    ui.click("leaveButton");
                });
                ProtocolMessage leave = read(input);
                assertEquals(LEAVE_ROOM, leave.type());
                send(peer, reply(leave, LEAVE_ROOM_RESULT, ROOM, "OK", null, Map.of()));
                ui.awaitText("joinMessage", "You left the room."::equals);
                onFx(() -> {
                    ui.assertPage("joinView");
                    ui.click("disconnectButton");
                    ui.assertPage("connectionView");
                });
                assertNull(input.readLine(), "Disconnect must close the same socket used for the whole flow");
            }
        }
    }

    static void connect(Ui ui, FakeTransport transport) throws Exception {
        onFx(() -> ui.startConnection("localhost", 5000));
        transport.completeConnect();
        onFx(() -> ui.assertPage("joinView"));
    }

    private static void join(Ui ui, FakeTransport transport) throws Exception {
        onFx(() -> ui.startJoin(ROOM, "Amal"));
        transport.latest().respond(JOIN_ROOM_RESULT, "OK", null, Map.of("playerId", "server-player"));
        onFx(() -> ui.assertPage("lobbyView"));
    }

    private static Map<String, Object> roster(String... players) {
        return Map.of("players", List.of(players).stream().map(name -> Map.of("playerName", name)).toList());
    }

    static ProtocolMessage pollStarted(String pollId, String question) {
        return event(POLL_STARTED, Map.of("pollId", pollId, "question", question,
                "options", Map.of("A", "TCP", "B", "UDP"), "counts", Map.of("A", 0, "B", 0)));
    }

    static ProtocolMessage counts(String type, String pollId, int a, int b) {
        return event(type, Map.of("pollId", pollId, "counts", Map.of("A", a, "B", b)));
    }

    static ProtocolMessage event(String type, Map<String, Object> payload) {
        return new ProtocolMessage(type, null, ROOM, null, null, payload);
    }

    static ProtocolMessage reply(ProtocolMessage request, String type, String room, String status, String error,
                                         Map<String, Object> payload) {
        return new ProtocolMessage(type, request.requestId(), room, status, error, payload);
    }

    static ProtocolMessage read(BufferedReader input) throws IOException {
        String line = input.readLine();
        assertNotNull(line, "Expected a request over the player's TCP socket");
        return MessageCodec.decodeJson(line.getBytes(StandardCharsets.UTF_8));
    }

    static void send(Socket socket, ProtocolMessage message) throws IOException {
        socket.getOutputStream().write(MessageCodec.encodeLine(message));
    }

    static void onFx(Runnable action) throws Exception {
        fxResult(() -> { action.run(); return null; });
    }

    static <T> T fxResult(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(6, SECONDS);
        } catch (TimeoutException failure) {
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                if (thread.getName().equals("JavaFX Application Thread")) {
                    System.err.println(thread.getName() + " " + thread.getState());
                    for (StackTraceElement element : stack) {
                        System.err.println("    at " + element);
                    }
                }
            });
            throw failure;
        }
    }

    record Ui(Parent root, PlayerSessionController session) implements AutoCloseable {
        static Ui open(PlayerTransport transport) throws Exception {
            return fxResult(() -> {
                var loader = new FXMLLoader(Ui.class.getResource("/fxml/player/PlayerShell.fxml"));
                Parent root = loader.load();
                PlayerSessionController controller = loader.getController();
                controller.bind(transport);
                new Scene(root, 1000, 690);
                root.applyCss();
                root.layout();
                return new Ui(root, controller);
            });
        }

        Node node(String id) {
            Node found = root.lookup("#" + id);
            assertNotNull(found, "Missing FXML node " + id);
            return found;
        }

        TextField input(String id) { return (TextField) node(id); }
        Button button(String id) { return (Button) node(id); }
        void click(String id) { button(id).fire(); }
        String text(String id) { return ((Label) node(id)).getText(); }
        List<?> roster() { return List.copyOf(((ListView<?>) node("roster")).getItems()); }

        RadioButton option(String id) {
            return node("pollOptions").lookupAll(".radio-button").stream().map(RadioButton.class::cast)
                    .filter(button -> id.equals(button.getUserData())).findFirst().orElseThrow();
        }

        List<String> optionCounts() {
            return node("pollOptions").lookupAll(".option-count").stream().map(Label.class::cast).map(Label::getText).sorted().toList();
        }

        void submit(String id) {
            option(id).fire();
            click("submitVoteButton");
        }

        void startConnection(String host, int port) {
            input("serverHost").setText(host);
            input("serverPort").setText(Integer.toString(port));
            click("connectButton");
        }

        void startJoin(String room, String name) {
            input("roomCode").setText(room);
            input("playerName").setText(name);
            click("joinButton");
        }

        void assertPage(String expected) {
            for (String id : List.of("connectionView", "joinView", "lobbyView", "pollView")) {
                assertEquals(id.equals(expected), node(id).isVisible(), id);
                assertEquals(id.equals(expected), node(id).isManaged(), id);
            }
        }

        void awaitText(String id, Predicate<String> expected) throws Exception {
            var found = new CompletableFuture<Void>();
            ChangeListener<String> listener = (property, previous, value) -> {
                if (expected.test(value)) {
                    found.complete(null);
                }
            };
            onFx(() -> {
                ((Label) node(id)).textProperty().addListener(listener);
                if (expected.test(text(id))) {
                    found.complete(null);
                }
            });
            try {
                found.get(6, SECONDS);
            } finally {
                onFx(() -> ((Label) node(id)).textProperty().removeListener(listener));
            }
        }

        @Override public void close() throws Exception { onFx(session::close); }
    }

    record Pending(ProtocolMessage request, CompletableFuture<ProtocolMessage> response) {
        void respond(String type, String status, String error, Map<String, Object> payload) {
            respond(type, request.roomCode(), status, error, payload);
        }

        void respond(String type, String room, String status, String error, Map<String, Object> payload) {
            response.complete(reply(request, type, room, status, error, payload));
        }

        void acceptVote() {
            respond(SUBMIT_VOTE_RESULT, "OK", null, Map.of("pollId", request.payload().get("pollId"),
                    "optionId", request.payload().get("optionId"), "accepted", true));
        }
    }

    static final class FakeTransport implements PlayerTransport {
        final List<Pending> requests = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<Void> connecting;
        volatile Consumer<ProtocolMessage> eventListener;
        volatile Consumer<IOException> lossListener;
        volatile boolean connected;
        int connects;
        int eventRemovals;
        int lossRemovals;
        boolean closed;
        ServerAddress address;

        @Override
        public CompletableFuture<Void> connect(String host, int port) {
            address = new ServerAddress(host, port);
            connects++;
            connecting = new CompletableFuture<>();
            return connecting;
        }

        void completeConnect() {
            connected = true;
            connecting.complete(null);
        }

        @Override public boolean isConnected() { return connected; }

        @Override
        public CompletableFuture<ProtocolMessage> request(ProtocolMessage message) {
            var pending = new Pending(message, new CompletableFuture<>());
            requests.add(pending);
            return pending.response();
        }

        Pending latest() { return requests.getLast(); }

        @Override
        public AutoCloseable subscribe(Consumer<ProtocolMessage> listener) {
            assertNull(eventListener, "Only one room subscription should be active");
            eventListener = listener;
            return () -> { eventListener = null; eventRemovals++; };
        }

        @Override
        public AutoCloseable onConnectionLost(Consumer<IOException> listener) {
            assertNull(lossListener, "Only one loss subscription should be active");
            lossListener = listener;
            return () -> { lossListener = null; lossRemovals++; };
        }

        void emit(ProtocolMessage payload) { eventListener.accept(payload); }

        void lose() {
            connected = false;
            lossListener.accept(new IOException("Test connection closed"));
        }

        @Override public void disconnect() { connected = false; }
        @Override public void close() { closed = true; disconnect(); }
    }
}
