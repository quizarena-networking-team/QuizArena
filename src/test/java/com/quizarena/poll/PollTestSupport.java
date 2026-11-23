package com.quizarena.poll;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;
import com.quizarena.quiz.PlayerDirectory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static com.quizarena.protocol.MessageTypes.SUBMIT_VOTE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class PollTestSupport {
    static final String ROOM = "ABC123";
    static final String QUESTION = "Which transport should we discuss next?";
    static final Map<String, String> OPTIONS = Map.of("A", "TCP", "B", "UDP", "C", "TLS");

    private PollTestSupport() {}

    static final class Directory implements PlayerDirectory {
        final Map<String, Player> joined = new ConcurrentHashMap<>();

        void join(String playerId) {
            joined.put(playerId, new Player(playerId, "Player " + playerId));
        }

        @Override
        public Optional<Player> find(String roomCode, String playerId) {
            return ROOM.equals(roomCode) ? Optional.ofNullable(joined.get(playerId)) : Optional.empty();
        }

        @Override
        public List<Player> players(String roomCode) {
            return ROOM.equals(roomCode) ? List.copyOf(joined.values()) : List.of();
        }
    }

    static final class Fixture implements AutoCloseable {
        final Directory directory = new Directory();
        final List<ProtocolMessage> events = new CopyOnWriteArrayList<>();
        final PollService service = new PollService(ROOM, directory, events::add);
        final PollRequestHandler handler = new PollRequestHandler(service);

        Fixture() {
            directory.join("p1");
            directory.join("p2");
        }

        PollSnapshot start() {
            return service.startPoll(QUESTION, OPTIONS);
        }

        ProtocolMessage handle(String playerId, ProtocolMessage request) {
            return handle(playerId, ROOM, request);
        }

        ProtocolMessage handle(String playerId, String contextRoom, ProtocolMessage request) {
            var context = new Context(playerId, contextRoom);
            handler.handle(request, context);
            return context.response();
        }

        @Override
        public void close() {
            service.close();
        }
    }

    static final class Context implements RequestContext {
        private final String playerId;
        private final String roomCode;
        private ProtocolMessage reply;

        Context(String playerId, String roomCode) {
            this.playerId = playerId;
            this.roomCode = roomCode;
        }

        @Override public String connectionId() { return "test-connection-" + playerId; }
        @Override public String playerId() { return playerId; }
        @Override public String roomCode() { return roomCode; }

        @Override
        public void reply(ProtocolMessage response) {
            assertNull(reply, "A request must have at most one reply");
            reply = response;
        }

        @Override public void sendToPlayer(String playerId, ProtocolMessage event) { fail("Poll events use the event sink"); }
        @Override public void broadcastToRoom(String roomCode, ProtocolMessage event) { fail("Poll events use the event sink"); }

        ProtocolMessage response() {
            assertNotNull(reply, "A dispatched request must have a reply");
            return reply;
        }
    }

    static ProtocolMessage vote(String requestId, String pollId, String optionId) {
        return new ProtocolMessage(SUBMIT_VOTE, requestId, ROOM, null, null,
                Map.of("pollId", pollId, "optionId", optionId));
    }

    static void rejected(String code, Runnable operation) {
        assertEquals(code, assertThrows(PollException.class, operation::run).code());
    }

    static <T> List<T> race(int count, IntFunction<T> operation) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> pending = new ArrayList<>(count);
            try {
                for (int i = 0; i < count; i++) {
                    int index = i;
                    pending.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, SECONDS), "Timed out starting concurrent calls");
                        return operation.apply(index);
                    }));
                }
                assertTrue(ready.await(5, SECONDS), "Concurrent callers did not arrive");
            } finally {
                start.countDown();
            }
            List<T> results = new ArrayList<>(count);
            for (Future<T> task : pending) {
                results.add(task.get(5, SECONDS));
            }
            return results;
        }
    }
}
