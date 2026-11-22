package com.quizarena.quiz;

import com.quizarena.db.QuizDao;
import com.quizarena.db.ResultDao;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.quizarena.protocol.MessageTypes.*;

/**
 * One service per room/session. All transitions and event enqueueing share one lock.
 * Public methods return futures and never wait for JDBC or workers on the caller thread.
 * Host methods are internal APIs: M5 must authenticate, authorize room ownership and
 * exclude an active poll before invoking them. They are never exposed by the TCP handler.
 */
public final class QuizService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(QuizService.class.getName());
    private final Object lock = new Object();
    private final String roomCode;
    private final long sessionId;
    private final PlayerDirectory players;
    private final GameEventSink events;
    private final QuizDao quizzes;
    private final ResultDao results;
    private final QuizClock clock;
    private final QuestionScheduler scheduler;
    private final ExecutorService workers;
    private final ScoreService scores = new ScoreService();
    private final BuzzerService buzzer = new BuzzerService();
    private final Set<String> answered = new HashSet<>();
    private final Set<String> participants = new HashSet<>();
    private QuizState state = QuizState.LOBBY;
    private Quiz quiz;
    private List<Question> standardQuestions = List.of();
    private List<ScoreService.Entry> revealedScores = List.of();
    private Question current;
    private Question buzzerQuestion;
    private boolean buzzerUsed;
    private int questionIndex;
    private String roundId;
    private long deadlineNanos;
    private long deadlineEpochMs;
    private long timerGeneration;
    private Duration buzzerAnswerWindow;
    private QuestionScheduler.Ticket timer;
    private Instant startedAt;
    private ResultSnapshot finalSnapshot;

    public QuizService(String roomCode, long sessionId, PlayerDirectory players,
                       GameEventSink events, QuizDao quizzes, ResultDao results) {
        this(roomCode, sessionId, players, events, quizzes, results,
                QuizClock.system(), QuestionScheduler.system(), newWorkers());
    }

    QuizService(String roomCode, long sessionId, PlayerDirectory players,
                GameEventSink events, QuizDao quizzes, ResultDao results,
                QuizClock clock, QuestionScheduler scheduler, ExecutorService workers) {
        if (roomCode == null || roomCode.isBlank() || sessionId <= 0) {
            throw new IllegalArgumentException("Room and existing database session ID are required");
        }
        this.roomCode = roomCode;
        this.sessionId = sessionId;
        this.players = Objects.requireNonNull(players);
        this.events = Objects.requireNonNull(events);
        this.quizzes = Objects.requireNonNull(quizzes);
        this.results = Objects.requireNonNull(results);
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.workers = Objects.requireNonNull(workers);
    }

    private static ExecutorService newWorkers() {
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), r -> {
            var thread = new Thread(r, "m3-quiz-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public String roomCode() { return roomCode; }
    public QuizState state() { synchronized (lock) { return state; } }

    public CompletableFuture<Void> startQuiz(String quizId) { return startQuiz(quizId, null); }

    /** Optional reserved question is excluded from normal rounds, so its answer has not been revealed. */
    public CompletableFuture<Void> startQuiz(String quizId, String reservedBuzzerQuestionId) {
        return async(() -> {
            synchronized (lock) {
                requireState(QuizState.LOBBY);
                requireText(quizId);
                state = QuizState.LOADING;
            }
            try {
                // Deliberately outside the room lock, on a worker; other rooms/connections keep running.
                Quiz loaded = Objects.requireNonNull(quizzes.load(quizId), "Quiz not found");
                if (!loaded.id().equals(quizId)) throw new IllegalArgumentException("Quiz ID mismatch");
                List<Question> normal = loaded.questions().stream()
                        .filter(q -> !q.id().equals(reservedBuzzerQuestionId)).toList();
                Question reserved = reservedBuzzerQuestionId == null ? null : loaded.questions().stream()
                        .filter(q -> q.id().equals(reservedBuzzerQuestionId)).findFirst()
                        .orElseThrow(() -> new QuizException("BAD_REQUEST", "Unknown buzzer question"));
                if (normal.isEmpty()) throw new QuizException("BAD_REQUEST", "A standard question is required");
                // Validate public frame sizes before changing gameplay state.
                for (Question question : loaded.questions()) {
                    MessageCodec.encodeLine(new ProtocolMessage(QUESTION_STARTED, null, roomCode,
                            null, null, questionPayload(question, "00000000-0000-0000-0000-000000000000",
                            "STANDARD", Long.MAX_VALUE)));
                }
                synchronized (lock) {
                    requireState(QuizState.LOADING);
                    var roster = List.copyOf(players.players(roomCode));
                    if (roster.isEmpty() || roster.size() > 10) {
                        throw new QuizException("BAD_REQUEST", "A quiz requires 1-10 players");
                    }
                    var ids = new HashSet<String>();
                    for (var player : roster) {
                        if (!ids.add(player.id())) throw new IllegalArgumentException("Duplicate roster identity");
                    }
                    quiz = loaded;
                    standardQuestions = normal;
                    buzzerQuestion = reserved;
                    roster.forEach(p -> { scores.register(p); participants.add(p.id()); });
                    revealedScores = scores.snapshot();
                    startedAt = Instant.ofEpochMilli(clock.epochMillis());
                    questionIndex = 0;
                    openQuestion();
                }
                return null;
            } catch (Exception failure) {
                synchronized (lock) {
                    if (state == QuizState.LOADING) state = QuizState.LOBBY;
                }
                throw safeFailure(failure);
            }
        });
    }

    public CompletableFuture<Void> nextQuestion() {
        return locked(() -> {
            expireIfDue();
            requireState(QuizState.QUESTION_REVIEW);
            requireUnfrozen();
            if (questionIndex + 1 >= standardQuestions.size()) {
                throw new QuizException("BAD_REQUEST", "No more standard questions; finish the quiz");
            }
            questionIndex++;
            openQuestion();
            return null;
        });
    }

    public CompletableFuture<Void> openBuzzer(Duration buzzWindow, Duration answerWindow) {
        return locked(() -> {
            expireIfDue();
            requireState(QuizState.QUESTION_REVIEW);
            requireUnfrozen();
            validateWindow(buzzWindow);
            validateWindow(answerWindow);
            if (buzzerQuestion == null || buzzerUsed) {
                throw new QuizException("BAD_REQUEST", "No unused reserved buzzer question");
            }
            current = buzzerQuestion;
            roundId = UUID.randomUUID().toString();
            answered.clear();
            buzzer.reset();
            buzzerUsed = true;
            buzzerAnswerWindow = answerWindow;
            state = QuizState.BUZZER_OPEN;
            arm(buzzWindow);
            emit(BUZZER_OPENED, questionPayload(current, roundId, "BUZZER", deadlineEpochMs));
            return null;
        });
    }

    public CompletableFuture<Map<String, Object>> submitAnswer(String playerId, String questionId,
                                                               String submittedRoundId, String optionId) {
        return locked(() -> {
            requirePlayer(playerId);
            expireIfDue();
            if (state != QuizState.QUESTION_OPEN && state != QuizState.BUZZER_AWAITING_ANSWER) {
                throw new QuizException("QUESTION_CLOSED", "Question is closed");
            }
            requireRound(questionId, submittedRoundId);
            if (!current.options().containsKey(optionId)) throw new QuizException("BAD_REQUEST", "Invalid option");
            if (state == QuizState.BUZZER_AWAITING_ANSWER && !playerId.equals(buzzer.winner())) {
                throw new QuizException("UNAUTHORIZED", "Only the buzzer winner may answer");
            }
            if (!answered.add(playerId)) {
                throw new QuizException("ANSWER_ALREADY_SUBMITTED", "Answer already submitted");
            }
            boolean buzzerAnswer = state == QuizState.BUZZER_AWAITING_ANSWER;
            scores.record(playerId, current.correctOption().equals(optionId), buzzerAnswer ? 50 : current.points());
            if (buzzerAnswer) endQuestion("ANSWERED");
            // No correctness, points or option echo while a normal question remains open.
            return Map.of("accepted", true, "questionId", questionId, "roundId", submittedRoundId);
        });
    }

    public CompletableFuture<Map<String, Object>> pressBuzzer(String playerId, String questionId,
                                                              String submittedRoundId) {
        return locked(() -> {
            requirePlayer(playerId);
            expireIfDue();
            if (state != QuizState.BUZZER_OPEN) throw new QuizException("BUZZER_CLOSED", "Buzzer is closed");
            requireRound(questionId, submittedRoundId);
            if (!buzzer.tryWin(playerId)) throw new QuizException("BUZZER_CLOSED", "Winner already selected");
            state = QuizState.BUZZER_AWAITING_ANSWER;
            arm(buzzerAnswerWindow);
            emit(BUZZER_WINNER, Map.of("questionId", current.id(), "roundId", roundId,
                    "playerId", playerId, "deadlineEpochMs", deadlineEpochMs));
            return Map.of("winner", true, "questionId", current.id(), "roundId", roundId);
        });
    }

    public CompletableFuture<Map<String, Object>> leaderboard(String playerId) {
        return locked(() -> {
            requirePlayer(playerId);
            expireIfDue();
            return leaderboardPayload();
        });
    }

    /** Retry after a failed save reuses the exact immutable snapshot; success is idempotent. */
    public CompletableFuture<ResultSnapshot> finishQuiz() {
        return async(() -> {
            ResultSnapshot snapshot;
            synchronized (lock) {
                ensureOpen();
                expireIfDue();
                if (state == QuizState.FINISHED) return finalSnapshot;
                requireState(QuizState.QUESTION_REVIEW);
                if (questionIndex != standardQuestions.size() - 1) {
                    throw new QuizException("BAD_REQUEST", "Complete all standard questions first");
                }
                if (finalSnapshot == null) {
                    finalSnapshot = new ResultSnapshot(sessionId, roomCode, quiz.id(), startedAt,
                            Instant.ofEpochMilli(Math.max(startedAt.toEpochMilli(), clock.epochMillis())),
                            scores.snapshot());
                }
                snapshot = finalSnapshot;
                state = QuizState.SAVING;
            }
            try {
                results.save(snapshot);
                synchronized (lock) {
                    if (state != QuizState.CLOSED) {
                        state = QuizState.FINISHED;
                        emit(QUIZ_FINISHED, Map.of("quizId", quiz.id(), "persisted", true,
                                "entries", publicEntries(snapshot.entries())));
                    }
                }
                return snapshot;
            } catch (Exception failure) {
                synchronized (lock) {
                    if (state == QuizState.SAVING) state = QuizState.QUESTION_REVIEW;
                }
                throw safeFailure(failure);
            }
        });
    }

    private void openQuestion() {
        current = standardQuestions.get(questionIndex);
        answered.clear();
        roundId = UUID.randomUUID().toString();
        state = QuizState.QUESTION_OPEN;
        arm(current.duration());
        emit(QUESTION_STARTED, questionPayload(current, roundId, "STANDARD", deadlineEpochMs));
    }

    private Map<String, Object> questionPayload(Question q, String id, String mode, long deadline) {
        return Map.of("questionId", q.id(), "roundId", id, "mode", mode,
                "text", q.text(), "options", q.options(), "deadlineEpochMs", deadline);
    }

    private void arm(Duration duration) {
        cancelTimer();
        deadlineNanos = clock.nanoTime() + duration.toNanos();
        deadlineEpochMs = clock.epochMillis() + duration.toMillis();
        long generation = timerGeneration;
        timer = scheduler.schedule(duration, () -> {
            synchronized (lock) {
                if (generation != timerGeneration || !roundOpen()) return;
                if (clock.nanoTime() - deadlineNanos >= 0) {
                    endQuestion("TIMEOUT");
                } else {
                    // A scheduler may fire slightly early; always enforce the actual monotonic deadline.
                    timer = scheduler.schedule(Duration.ofNanos(deadlineNanos - clock.nanoTime()),
                            () -> onDeadline(generation));
                }
            }
        });
    }

    private void onDeadline(long generation) {
        synchronized (lock) {
            if (generation != timerGeneration || !roundOpen()) return;
            if (clock.nanoTime() - deadlineNanos >= 0) endQuestion("TIMEOUT");
            else timer = scheduler.schedule(Duration.ofNanos(deadlineNanos - clock.nanoTime()),
                    () -> onDeadline(generation));
        }
    }

    private void expireIfDue() {
        if (roundOpen() && clock.nanoTime() - deadlineNanos >= 0) endQuestion("TIMEOUT");
    }

    private boolean roundOpen() {
        return state == QuizState.QUESTION_OPEN || state == QuizState.BUZZER_OPEN
                || state == QuizState.BUZZER_AWAITING_ANSWER;
    }

    private void endQuestion(String reason) {
        cancelTimer();
        state = QuizState.QUESTION_REVIEW;
        revealedScores = scores.snapshot();
        emit(QUESTION_ENDED, Map.of("questionId", current.id(), "roundId", roundId,
                "correctOptionId", current.correctOption(), "reason", reason));
        emit(LEADERBOARD_UPDATED, leaderboardPayload());
    }

    private void requireRound(String questionId, String id) {
        if (!current.id().equals(questionId) || !roundId.equals(id)) {
            throw new QuizException("QUESTION_CLOSED", "Request refers to an inactive round");
        }
    }

    private void requirePlayer(String playerId) {
        ensureOpen();
        if (playerId == null || players.find(roomCode, playerId).isEmpty()
                || (quiz != null && !participants.contains(playerId))) {
            throw new QuizException("INVALID_PLAYER", "Player is not an active quiz participant");
        }
    }

    private Map<String, Object> leaderboardPayload() {
        return Map.of("entries", publicEntries(revealedScores));
    }

    private List<Map<String, Object>> publicEntries(List<ScoreService.Entry> entries) {
        return entries.stream().map(ScoreService.Entry::publicPayload).toList();
    }

    private void emit(String type, Map<String, Object> payload) {
        try {
            events.broadcast(new ProtocolMessage(type, null, roomCode, null, null, payload));
        } catch (RuntimeException failure) {
            // A disconnected/slow client must not undo an accepted game transition.
            LOG.log(System.Logger.Level.ERROR, "Game event enqueue failed: " + type, failure);
        }
    }

    private void requireState(QuizState expected) {
        ensureOpen();
        if (state != expected) throw new QuizException("BAD_REQUEST", "Action is unavailable in " + state);
    }

    private void requireUnfrozen() {
        if (finalSnapshot != null) {
            throw new QuizException("BAD_REQUEST", "Results are frozen; retry finishing the quiz");
        }
    }

    private void ensureOpen() {
        if (state == QuizState.CLOSED) throw new QuizException("ROOM_CLOSED", "Quiz service is closed");
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new QuizException("BAD_REQUEST", "ID is required");
    }

    private static void validateWindow(Duration duration) {
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0
                || duration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new QuizException("BAD_REQUEST", "Duration must be 1-600 seconds");
        }
    }

    private <T> CompletableFuture<T> locked(Supplier<T> action) {
        return async(() -> { synchronized (lock) { ensureOpen(); return action.get(); } });
    }

    private <T> CompletableFuture<T> async(Supplier<T> action) {
        var future = new CompletableFuture<T>();
        try {
            workers.execute(() -> {
                try { future.complete(action.get()); }
                catch (Exception failure) { future.completeExceptionally(safeFailure(failure)); }
            });
        } catch (RejectedExecutionException failure) {
            future.completeExceptionally(new QuizException("INTERNAL_ERROR", "Quiz service is busy or closed"));
        }
        return future;
    }

    private static QuizException safeFailure(Exception failure) {
        if (failure instanceof QuizException expected) return expected;
        LOG.log(System.Logger.Level.ERROR, "Quiz operation failed", failure);
        return new QuizException("INTERNAL_ERROR", "Quiz operation failed; please retry");
    }

    private void cancelTimer() {
        timerGeneration++;
        if (timer != null) { timer.cancel(); timer = null; }
    }

    @Override public void close() {
        synchronized (lock) {
            if (state == QuizState.CLOSED) return;
            state = QuizState.CLOSED;
            cancelTimer();
        }
        scheduler.close();
        // Drain accepted tasks so their futures are completed with ROOM_CLOSED instead of hanging.
        workers.shutdown();
    }
}
