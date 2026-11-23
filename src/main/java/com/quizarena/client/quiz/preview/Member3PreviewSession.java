package com.quizarena.client.quiz.preview;

import com.quizarena.client.quiz.QuizClientPort;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;
import com.quizarena.quiz.PlayerDirectory;
import com.quizarena.quiz.Question;
import com.quizarena.quiz.Quiz;
import com.quizarena.quiz.QuizRequestHandler;
import com.quizarena.quiz.QuizService;
import com.quizarena.quiz.ResultSnapshot;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.quizarena.protocol.MessageTypes.*;

/** Local developer fixture only. Uses the actual engine/handler with sample data and memory adapters. */
public final class Member3PreviewSession implements AutoCloseable {
    public static final String ROOM = "M3DEMO";
    private final List<PlayerDirectory.Player> roster = IntStream.range(0, 5)
            .mapToObj(i -> new PlayerDirectory.Player("preview-player-" + i, "Player " + (i + 1))).toList();
    private final List<LocalClient> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger questionsStarted = new AtomicInteger();
    private final ExecutorService raceWorkers = Executors.newFixedThreadPool(5, action -> {
        var thread = new Thread(action, "m3-preview-race");
        thread.setDaemon(true);
        return thread;
    });
    private final QuizService service;
    private final QuizRequestHandler handler;
    private volatile ProtocolMessage activeQuestion;
    private volatile boolean buzzerUsed;
    private volatile ResultSnapshot saved;

    public Member3PreviewSession() { this(Duration.ofSeconds(15)); }

    public Member3PreviewSession(Duration questionDuration) {
        var directory = new PlayerDirectory() {
            public Optional<Player> find(String room, String id) {
                return ROOM.equals(room) ? roster.stream().filter(p -> p.id().equals(id)).findFirst() : Optional.empty();
            }
            public List<Player> players(String room) { return ROOM.equals(room) ? roster : List.of(); }
        };
        Quiz sample = sampleQuiz(questionDuration);
        service = new QuizService(ROOM, 1, directory, this::broadcast, id -> sample, snapshot -> saved = snapshot);
        handler = new QuizRequestHandler(service);
        roster.forEach(player -> clients.add(new LocalClient(player)));
    }

    public QuizService service() { return service; }
    public List<LocalClient> clients() { return List.copyOf(clients); }
    public int questionsStarted() { return questionsStarted.get(); }
    public boolean buzzerUsed() { return buzzerUsed; }
    public ResultSnapshot saved() { return saved; }
    public ProtocolMessage activeQuestion() { return activeQuestion; }

    public CompletableFuture<Void> start() { return service.startQuiz("preview", "buzzer"); }

    public CompletableFuture<Integer> raceBuzzers() {
        ProtocolMessage question = activeQuestion;
        if (question == null || !BUZZER_OPENED.equals(question.type())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Open a buzzer round first"));
        }
        var barrier = new CyclicBarrier(5);
        var replies = new java.util.ArrayList<CompletableFuture<ProtocolMessage>>();
        for (var client : clients) {
            var reply = new CompletableFuture<ProtocolMessage>();
            replies.add(reply);
            try {
                raceWorkers.execute(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        client.request(new ProtocolMessage(PRESS_BUZZER, UUID.randomUUID().toString(), ROOM, null, null,
                                Map.of("questionId", question.payload().get("questionId"),
                                        "roundId", question.payload().get("roundId"))))
                                .whenComplete((result, failure) -> {
                                    if (failure == null) reply.complete(result);
                                    else reply.completeExceptionally(failure);
                                });
                    } catch (Exception failure) { reply.completeExceptionally(failure); }
                });
            } catch (RuntimeException failure) { reply.completeExceptionally(failure); }
        }
        return CompletableFuture.allOf(replies.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> (int) replies.stream().map(CompletableFuture::join)
                        .filter(reply -> "OK".equals(reply.status())).count());
    }

    private void broadcast(ProtocolMessage event) {
        if (QUESTION_STARTED.equals(event.type())) {
            activeQuestion = event;
            questionsStarted.incrementAndGet();
        } else if (BUZZER_OPENED.equals(event.type())) {
            activeQuestion = event;
            buzzerUsed = true;
        }
        // Each listener receives the same JSON shape as a network client, with no sockets or fake scores.
        ProtocolMessage decoded = roundTrip(event);
        clients.forEach(client -> client.listeners.forEach(listener -> listener.accept(decoded)));
    }

    private static ProtocolMessage roundTrip(ProtocolMessage message) {
        try {
            byte[] frame = MessageCodec.encodeLine(message);
            return MessageCodec.decodeJson(Arrays.copyOf(frame, frame.length - 1));
        } catch (Exception failure) { throw new IllegalStateException("Preview message encoding failed", failure); }
    }

    public final class LocalClient implements QuizClientPort {
        private final PlayerDirectory.Player player;
        private final List<Consumer<ProtocolMessage>> listeners = new CopyOnWriteArrayList<>();
        private LocalClient(PlayerDirectory.Player player) { this.player = player; }
        public String playerId() { return player.id(); }
        public String playerName() { return player.name(); }

        @Override public CompletableFuture<ProtocolMessage> request(ProtocolMessage request) {
            var response = new CompletableFuture<ProtocolMessage>();
            try {
                handler.handle(roundTrip(request), new RequestContext() {
                    public String connectionId() { return player.id(); }
                    public String playerId() { return player.id(); }
                    public String roomCode() { return ROOM; }
                    public void reply(ProtocolMessage reply) {
                        try { response.complete(roundTrip(reply)); }
                        catch (RuntimeException failure) { response.completeExceptionally(failure); }
                    }
                    public void sendToPlayer(String id, ProtocolMessage event) { throw new UnsupportedOperationException(); }
                    public void broadcastToRoom(String room, ProtocolMessage event) { broadcast(event); }
                });
            } catch (RuntimeException failure) { response.completeExceptionally(failure); }
            return response;
        }

        @Override public AutoCloseable subscribe(Consumer<ProtocolMessage> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }

    private static Quiz sampleQuiz(Duration duration) {
        return new Quiz("preview", "Member 3 networking quiz", List.of(
                new Question("q1", "Which protocol provides a reliable, ordered byte stream?",
                        Map.of("A", "TCP", "B", "UDP", "C", "ICMP", "D", "ARP"), "A", 100, duration),
                new Question("q2", "Which Java NIO class monitors channel readiness?",
                        Map.of("A", "Scanner", "B", "Selector", "C", "File", "D", "StringBuilder"), "B", 100, duration),
                new Question("q3", "Which interface schedules a task to run after a delay?",
                        Map.of("A", "Map", "B", "Socket", "C", "ScheduledExecutorService", "D", "Iterator"), "C", 100, duration),
                new Question("buzzer", "Which operation can select exactly one buzzer winner?",
                        Map.of("A", "Changing a label", "B", "Disabling a client button", "C", "Sorting player names",
                                "D", "Atomic compare-and-set"), "D", 50, duration)));
    }

    @Override public void close() {
        clients.forEach(client -> client.listeners.clear());
        service.close();
        raceWorkers.shutdownNow();
    }
}
