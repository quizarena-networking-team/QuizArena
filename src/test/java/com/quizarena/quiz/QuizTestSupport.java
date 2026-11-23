package com.quizarena.quiz;

import com.quizarena.db.QuizDao;
import com.quizarena.db.ResultDao;
import com.quizarena.protocol.ProtocolMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

final class QuizTestSupport {
    static final String ROOM = "ABC123";

    static Question question(String id) {
        return new Question(id, "Question " + id, Map.of("A", "Alpha", "B", "Beta", "C", "Gamma", "D", "Delta"),
                "A", 100, Duration.ofSeconds(20));
    }

    static Quiz fixture() {
        return new Quiz("1", "Networking", List.of(question("1"), question("2"), question("3"), question("4")));
    }

    static final class Clock implements QuizClock {
        final AtomicLong nanos = new AtomicLong();
        final AtomicLong wall = new AtomicLong(1_800_000_000_000L);
        public long nanoTime() { return nanos.get(); }
        public long epochMillis() { return wall.get(); }
        void advance(Duration duration) { nanos.addAndGet(duration.toNanos()); wall.addAndGet(duration.toMillis()); }
    }

    static final class Scheduler implements QuestionScheduler {
        static final class Job {
            long due;
            Runnable action;
            boolean cancelled;
            boolean ran;
        }
        final Clock clock;
        final List<Job> jobs = new ArrayList<>();
        Scheduler(Clock clock) { this.clock = clock; }
        public synchronized Ticket schedule(Duration duration, Runnable action) {
            var job = new Job();
            job.due = clock.nanoTime() + duration.toNanos();
            job.action = action;
            jobs.add(job);
            return () -> job.cancelled = true;
        }
        synchronized void runDue() {
            for (var job : List.copyOf(jobs)) {
                if (!job.cancelled && !job.ran && job.due <= clock.nanoTime()) {
                    job.ran = true;
                    job.action.run();
                }
            }
        }
        public void close() {}
    }

    static final class Directory implements PlayerDirectory {
        final Map<String, Player> roster = new ConcurrentHashMap<>();
        Directory() { for (int i = 0; i < 5; i++) roster.put("p" + i, new Player("p" + i, "Player " + i)); }
        public Optional<Player> find(String room, String id) {
            return ROOM.equals(room) && id != null ? Optional.ofNullable(roster.get(id)) : Optional.empty();
        }
        public List<Player> players(String room) { return List.copyOf(roster.values()); }
    }

    static final class Harness implements AutoCloseable {
        final Clock clock = new Clock();
        final Scheduler scheduler = new Scheduler(clock);
        final Directory directory = new Directory();
        final List<ProtocolMessage> events = new CopyOnWriteArrayList<>();
        final List<ResultSnapshot> saved = new CopyOnWriteArrayList<>();
        final QuizService service;
        Harness() { this(id -> fixture(), null); }
        Harness(QuizDao dao, ResultDao resultDao) {
            service = new QuizService(ROOM, 1, directory, events::add, dao,
                    resultDao == null ? saved::add : resultDao, clock, scheduler, Executors.newFixedThreadPool(4));
        }
        void start() { await(service.startQuiz("1", "4")); }
        ProtocolMessage last(String type) {
            return events.stream().filter(e -> e.type().equals(type)).reduce((a, b) -> b).orElseThrow();
        }
        String round() {
            return (String) events.stream().filter(e -> e.payload().containsKey("roundId"))
                    .reduce((a, b) -> b).orElseThrow().payload().get("roundId");
        }
        void expire() { clock.advance(Duration.ofSeconds(20)); scheduler.runDue(); }
        void completeStandardQuestions() {
            for (int i = 0; i < 3; i++) {
                expire();
                if (i < 2) await(service.nextQuestion());
            }
        }
        public void close() { service.close(); }
    }

    static <T> T await(CompletableFuture<T> future) {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    static void error(String code, CompletableFuture<?> future) {
        var failure = assertThrows(CompletionException.class, future::join);
        assertEquals(code, assertInstanceOf(QuizException.class, failure.getCause()).code());
    }

    static List<Boolean> race(int count, IntFunction<CompletableFuture<?>> action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var ready = new CountDownLatch(count);
            var go = new CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(5, TimeUnit.SECONDS));
                    try { action.apply(index).join(); return true; }
                    catch (CompletionException rejected) { return false; }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            var accepted = new ArrayList<Boolean>();
            for (var result : results) accepted.add(result.get(5, TimeUnit.SECONDS));
            return accepted;
        }
    }
}
