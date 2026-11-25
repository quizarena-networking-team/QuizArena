package com.quizarena.server;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;

import java.io.IOException;
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
 *   Member 4:  subscribe to services.events() for the network inspector, start UDP/multicast services
 * </pre>
 *
 * Run: {@code QUIZ_DEV_ROOM=DEMO01 java ...ServerMain} (or {@code --dev-room=DEMO01 --port=5000})
 * to pre-create a room so clients can join before the TLS host service exists.
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

    public static void main(String[] args) throws IOException, InterruptedException {
        ServerConfig config = applyArguments(ServerConfig.fromEnv(System.getenv()), args);

        System.out.println("============================");
        System.out.println("    QuizArena Server");
        System.out.println("============================");

        Services services = assemble(config);
        services.events().subscribe(event -> {
            boolean chatter = event.kind() == NetworkEvent.Kind.READ || event.kind() == NetworkEvent.Kind.WRITE;
            if (!chatter || config.logIo()) {
                System.out.println(event);
            }
        });

        services.server().start();
        System.out.printf("Gameplay TCP (NIO) listening on %s:%d%n", config.bindHost(), services.server().boundPort());

        if (config.devRoomCode() != null) {
            String code = services.rooms().createRoom(config.devRoomCode());
            System.out.println("Development room created: " + code
                    + "  (players can JOIN_ROOM with this code; TLS host control is not available yet)");
        }
        System.out.println("Press Ctrl+C to stop.");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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
