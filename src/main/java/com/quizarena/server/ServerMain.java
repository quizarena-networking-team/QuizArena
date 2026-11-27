package com.quizarena.server;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.security.SecureHostRuntime;
import com.quizarena.server.inspector.NetworkInspectorWindow;
import com.quizarena.udp.LanServices;
import com.quizarena.udp.UdpConfig;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the QuizArena backend.
 *
 * Member 1 owns the NIO gameplay gateway wired up here. Other members add their services in
 * {@link #assemble(ServerConfig)} (see the marked extension points) and register their request
 * handlers on the shared {@link MessageDispatcher}:
 *
 * <pre>
 *   Member 2:  dispatcher.register(pollHandler.supportedTypes(), pollHandler);   // SUBMIT_VOTE
 *   Member 3:  dispatcher.register(quizHandler.supportedTypes(), quizHandler);   // SUBMIT_ANSWER, PRESS_BUZZER, GET_LEADERBOARD
 *   Member 5:  start the TLS host server and call rooms.createRoom()/closeRoom() from HostCommandCoordinator
 *   Member 4:  LanServices (UDP discovery, heartbeat, multicast) is started in main(); when a room's
 *              Poll/Quiz services are created, pass lan.wrap(rooms.eventSink(code)) as their
 *              GameEventSink so game phases are also announced over multicast
 * </pre>
 *
 * Run: {@code QUIZ_DEV_ROOM=DEMO01 java ...ServerMain} (or {@code --dev-room=DEMO01 --port=5000})
 * to pre-create a room so clients can join before the TLS host service exists.
 * Add {@code --inspector} to open the host-only network inspector window.
 */
public final class ServerMain {

    /** Everything other modules need to plug in. */
    public record Services(
            ServerConfig config,
            NetworkEventBus events,
            RoomRegistry rooms,
            MessageDispatcher dispatcher,
            NioQuizServer server
    ) implements AutoCloseable {
        @Override
        public void close() {
            server.stop();
        }
    }

    private ServerMain() {
    }

    /**
     * Builds (but does not start) the gameplay server with Member 1's own handlers registered.
     */
    public static Services assemble(ServerConfig config) {
        NetworkEventBus events = new NetworkEventBus();
        RoomRegistry rooms = new RoomRegistry(config.maxPlayersPerRoom(), 1);
        MessageDispatcher dispatcher = new MessageDispatcher();

        RoomRequestHandler roomHandler = new RoomRequestHandler();
        dispatcher.register(roomHandler.supportedTypes(), roomHandler);

        // ---- Extension point: other members register their gameplay handlers here ----
        // Member 2: PollRequestHandler (SUBMIT_VOTE)     -> needs a PollService per room
        // Member 3: QuizRequestHandler (SUBMIT_ANSWER, PRESS_BUZZER, GET_LEADERBOARD)
        // Both services take rooms (PlayerDirectory) and rooms.eventSink(roomCode) (GameEventSink).

        NioQuizServer server = new NioQuizServer(config, dispatcher, rooms, events);
        return new Services(config, events, rooms, dispatcher, server);
    }

    public static void main(String[] args) throws IOException, InterruptedException, SQLException {
        boolean inspector = Arrays.asList(args).contains("--inspector");
        String[] settings = Arrays.stream(args).filter(arg -> !arg.equals("--inspector")).toArray(String[]::new);
        ServerConfig config = applyArguments(ServerConfig.fromEnv(System.getenv()), settings);
        UdpConfig udp = UdpConfig.fromEnv(System.getenv());

        System.out.println("============================");
        System.out.println("    QuizArena Server");
        System.out.println("============================");

        Services services = assemble(config);
        services.events().subscribe(event -> {
            boolean chatter = event.kind() == NetworkEvent.Kind.READ || event.kind() == NetworkEvent.Kind.WRITE
                    || (event.kind() == NetworkEvent.Kind.UDP_RECEIVED && String.valueOf(event.detail()).startsWith("HEARTBEAT"));
            if (!chatter || config.logIo()) {
                System.out.println(event);
            }
        });

        services.server().start();
        System.out.printf("Gameplay TCP (NIO) listening on %s:%d%n", config.bindHost(), services.server().boundPort());

        SecureHostRuntime secureHosts = SecureHostRuntime.isEnabled(System.getenv())
                ? SecureHostRuntime.start(System.getenv(), services.rooms(), services.dispatcher())
                : null;
        if (secureHosts != null) {
            System.out.printf("Secure host control (TLS 1.3) listening on port %d%n", secureHosts.boundPort());
        } else {
            System.out.println("Secure host control disabled (set QUIZ_TLS_ENABLED=true to enable it)");
        }

        if (config.devRoomCode() != null) {
            String code = services.rooms().createRoom(config.devRoomCode());
            System.out.println("Development room created: " + code
                    + "  (players can JOIN_ROOM with this code; TLS host control is not available yet)");
        }

        // Member 4: supplementary UDP services. Failures are reported as events; TCP keeps running.
        LanServices lan = LanServices.start(udp, services.server().boundPort(), services.rooms(), services.events());
        System.out.printf("UDP discovery %s, heartbeat %s, multicast %s%n",
                lan.discoveryPort() > 0 ? "on port " + lan.discoveryPort() : "unavailable",
                lan.heartbeatPort() > 0 ? "on port " + lan.heartbeatPort() : "unavailable",
                lan.multicastAvailable() ? "to " + udp.multicastGroup() + ":" + udp.multicastPort() : "unavailable");
        if (inspector) {
            NetworkInspectorWindow.open(services.events(), lan.multicastAvailable() ? lan::announce : null,
                    config.devRoomCode());
        }
        System.out.println("Press Ctrl+C to stop.");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (secureHosts != null) secureHosts.close();
            lan.close();
            services.close();
            stopped.countDown();
        }, "quiz-shutdown"));
        stopped.await();
    }

    /** Command-line overrides: {@code --port=5000}, {@code --host=0.0.0.0}, {@code --dev-room=DEMO01}. */
    static ServerConfig applyArguments(ServerConfig base, String[] args) {
        Map<String, String> overrides = new HashMap<>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (!arg.startsWith("--") || equals < 3) {
                throw new IllegalArgumentException("Unknown argument: " + arg
                        + " (expected --port=N, --host=H or --dev-room=CODE)");
            }
            overrides.put(arg.substring(2, equals), arg.substring(equals + 1));
        }
        ServerConfig config = base;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            config = switch (entry.getKey()) {
                case "port" -> config.withTcpPort(parsePort(entry.getValue()));
                case "host" -> config.withBindHost(entry.getValue());
                case "dev-room" -> config.withDevRoomCode(entry.getValue());
                default -> throw new IllegalArgumentException("Unknown argument: --" + entry.getKey());
            };
        }
        return config;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("--port must be a whole number, got: " + value);
        }
    }
}
