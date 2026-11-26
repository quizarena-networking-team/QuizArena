package com.quizarena.security;

import com.quizarena.db.QuizDao;
import com.quizarena.db.ResultDao;
import com.quizarena.poll.PollException;
import com.quizarena.poll.PollService;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.quiz.QuizException;
import com.quizarena.quiz.QuizService;
import com.quizarena.quiz.QuizState;
import com.quizarena.security.tls.AuthenticatedTlsCommandHandler;
import com.quizarena.security.tls.TlsSessionContext;
import com.quizarena.server.RoomException;
import com.quizarena.server.RoomRegistry;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

import static com.quizarena.protocol.MessageTypes.*;

/** Owns trusted host-to-room authorization and cross-feature lifecycle rules. */
public final class HostCommandCoordinator implements AuthenticatedTlsCommandHandler, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(HostCommandCoordinator.class.getName());

    private static final class HostRoom implements AutoCloseable {
        final long hostId;
        final long sessionId;
        final String roomCode;
        final PollService polls;
        final QuizService quiz;
        boolean quizStartPending;

        HostRoom(long hostId, long sessionId, String roomCode, PollService polls, QuizService quiz) {
            this.hostId = hostId;
            this.sessionId = sessionId;
            this.roomCode = roomCode;
            this.polls = polls;
            this.quiz = quiz;
        }

        @Override public void close() {
            polls.close();
            quiz.close();
        }
    }

    private final Object lock = new Object();
    private final RoomRegistry rooms;
    private final QuizSessionStore sessions;
    private final QuizDao quizzes;
    private final ResultDao results;
    private final Map<String, HostRoom> activeRooms = new HashMap<>();
    private boolean closed;

    public HostCommandCoordinator(RoomRegistry rooms, QuizSessionStore sessions,
                                  QuizDao quizzes, ResultDao results) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.quizzes = Objects.requireNonNull(quizzes, "quizzes");
        this.results = Objects.requireNonNull(results, "results");
    }

    @Override
    public void handle(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) {
        try {
            switch (request.type()) {
                case CREATE_ROOM -> createRoom(request, session, host);
                case START_POLL -> startPoll(request, session, host);
                case CLOSE_POLL -> closePoll(request, session, host);
                case START_QUIZ -> startQuiz(request, session, host);
                case NEXT_QUESTION -> nextQuestion(request, session, host);
                case OPEN_BUZZER -> openBuzzer(request, session, host);
                case FINISH_QUIZ -> finishQuiz(request, session, host);
                default -> replyError(session, request, "BAD_REQUEST", "Unsupported host command");
            }
        } catch (RoomException | PollException | QuizException failure) {
            String code = failure instanceof RoomException room ? room.code()
                    : failure instanceof PollException poll ? poll.code()
                    : ((QuizException) failure).code();
            replyError(session, request, code, failure.getMessage());
        } catch (SQLException failure) {
            LOG.log(System.Logger.Level.ERROR, "Database operation failed for " + request.type(), failure);
            replyError(session, request, "INTERNAL_ERROR", "The server could not complete the database operation");
        } catch (IllegalArgumentException failure) {
            replyError(session, request, "BAD_REQUEST", failure.getMessage());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Host command failed: " + request.type(), failure);
            replyError(session, request, "INTERNAL_ERROR", "The server could not complete the request");
        }
    }

    private void createRoom(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host)
            throws SQLException {
        String roomCode;
        long sessionId;
        synchronized (lock) {
            ensureOpen();
            if (activeRooms.values().stream().anyMatch(room -> room.hostId == host.id())) {
                throw new IllegalArgumentException("This host already owns an open room");
            }
            roomCode = rooms.createRoom();
            try {
                sessionId = sessions.create(host.id(), roomCode);
                var polls = new PollService(roomCode, rooms, rooms.eventSink(roomCode));
                var quiz = new QuizService(roomCode, sessionId, rooms, rooms.eventSink(roomCode), quizzes, results);
                activeRooms.put(roomCode, new HostRoom(host.id(), sessionId, roomCode, polls, quiz));
            } catch (SQLException | RuntimeException failure) {
                rooms.closeRoom(roomCode);
                throw failure;
            }
        }
        replyOk(session, request, roomCode,
                Map.of("roomCode", roomCode, "sessionId", Long.toString(sessionId)));
    }

    private void startPoll(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) {
        String question = text(request.payload(), "question");
        Map<String, String> options = stringMap(request.payload().get("options"), "options");
        String roomCode;
        String pollId;
        synchronized (lock) {
            HostRoom room = ownedRoom(request, host);
            QuizState state = room.quiz.state();
            if (room.quizStartPending || (state != QuizState.LOBBY && state != QuizState.FINISHED)) {
                throw new IllegalArgumentException("Polls are allowed only before or after a quiz");
            }
            var poll = room.polls.startPoll(question, options);
            roomCode = room.roomCode;
            pollId = poll.pollId();
        }
        replyOk(session, request, roomCode, Map.of("pollId", pollId));
    }

    private void closePoll(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) {
        String roomCode;
        String pollId;
        Map<String, Integer> counts;
        synchronized (lock) {
            HostRoom room = ownedRoom(request, host);
            var poll = room.polls.closePoll(text(request.payload(), "pollId"));
            roomCode = room.roomCode;
            pollId = poll.pollId();
            counts = poll.counts();
        }
        replyOk(session, request, roomCode, Map.of("pollId", pollId, "counts", counts));
    }

    private void startQuiz(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) {
        String quizId = text(request.payload(), "quizId");
        String buzzerQuestionId = optionalText(request.payload().get("buzzerQuestionId"));
        HostRoom room;
        synchronized (lock) {
            room = ownedRoom(request, host);
            if (room.quizStartPending) throw new QuizException("BAD_REQUEST", "Quiz start is already in progress");
            if (room.polls.currentPoll()
                    .filter(poll -> poll.state() == com.quizarena.poll.PollSnapshot.State.OPEN).isPresent()) {
                throw new QuizException("BAD_REQUEST", "Close the active poll before starting the quiz");
            }
            room.quizStartPending = true;
        }
        room.quiz.startQuiz(quizId, buzzerQuestionId).whenComplete((ignored, failure) -> {
            synchronized (lock) { room.quizStartPending = false; }
            complete(session, request, room.roomCode, failure, Map.of("quizId", quizId));
        });
    }

    private void nextQuestion(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) {
        HostRoom room = ownedRoom(request, host);
        room.quiz.nextQuestion().whenComplete((ignored, failure) ->
                complete(session, request, room.roomCode, failure, Map.of()));
    }

    private void openBuzzer(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) {
        HostRoom room = ownedRoom(request, host);
        Duration buzz = Duration.ofSeconds(wholeNumber(request.payload(), "buzzDurationSeconds", 1, 600));
        Duration answer = Duration.ofSeconds(wholeNumber(request.payload(), "answerDurationSeconds", 1, 600));
        room.quiz.openBuzzer(buzz, answer).whenComplete((ignored, failure) ->
                complete(session, request, room.roomCode, failure, Map.of()));
    }

    private void finishQuiz(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) {
        HostRoom room = ownedRoom(request, host);
        room.quiz.finishQuiz().whenComplete((snapshot, failure) ->
                complete(session, request, room.roomCode, failure, Map.of("persisted", true)));
    }

    private HostRoom ownedRoom(ProtocolMessage request, AuthenticatedHost host) {
        synchronized (lock) {
            ensureOpen();
            if (request.roomCode() == null || request.roomCode().isBlank()) {
                throw new IllegalArgumentException("roomCode is required");
            }
            HostRoom room = activeRooms.get(request.roomCode().strip().toUpperCase());
            if (room == null) throw new RoomException("ROOM_NOT_FOUND", "Room does not exist");
            if (room.hostId != host.id()) throw new RoomException("UNAUTHORIZED", "Host does not own this room");
            return room;
        }
    }

    private void complete(TlsSessionContext session, ProtocolMessage request, String roomCode,
                          Throwable failure, Map<String, Object> payload) {
        if (failure == null) {
            replyOk(session, request, roomCode, payload);
            return;
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof PollException poll) replyError(session, request, poll.code(), poll.getMessage());
        else if (cause instanceof QuizException quiz) replyError(session, request, quiz.code(), quiz.getMessage());
        else if (cause instanceof IllegalArgumentException) replyError(session, request, "BAD_REQUEST", cause.getMessage());
        else {
            LOG.log(System.Logger.Level.ERROR, "Asynchronous host command failed: " + request.type(), cause);
            replyError(session, request, "INTERNAL_ERROR", "The server could not complete the request");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static void replyOk(TlsSessionContext session, ProtocolMessage request,
                                String roomCode, Map<String, Object> payload) {
        send(session, new ProtocolMessage(responseType(request.type()), request.requestId(), roomCode,
                "OK", null, payload));
    }

    private static void replyError(TlsSessionContext session, ProtocolMessage request, String code, String message) {
        send(session, new ProtocolMessage(responseType(request.type()), request.requestId(), request.roomCode(),
                "ERROR", code, Map.of("message", message == null ? "Request rejected" : message)));
    }

    private static void send(TlsSessionContext session, ProtocolMessage response) {
        try { session.send(response); }
        catch (IOException failure) { LOG.log(System.Logger.Level.DEBUG, "TLS reply could not be delivered", failure); }
    }

    private static String responseType(String type) {
        return switch (type) {
            case CREATE_ROOM -> CREATE_ROOM_RESULT;
            case START_POLL -> START_POLL_RESULT;
            case CLOSE_POLL -> CLOSE_POLL_RESULT;
            case START_QUIZ -> START_QUIZ_RESULT;
            case NEXT_QUESTION -> NEXT_QUESTION_RESULT;
            case OPEN_BUZZER -> OPEN_BUZZER_RESULT;
            case FINISH_QUIZ -> FINISH_QUIZ_RESULT;
            default -> type;
        };
    }

    private static String text(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(name + " is required");
        return text.strip();
    }

    private static String optionalText(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Invalid optional text");
        return text.strip();
    }

    private static Map<String, String> stringMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException(name + " is required");
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, label) -> {
            if (!(key instanceof String id) || !(label instanceof String text)) {
                throw new IllegalArgumentException(name + " must contain text keys and values");
            }
            result.put(id, text);
        });
        return result;
    }

    private static long wholeNumber(Map<String, Object> payload, String name, long minimum, long maximum) {
        Object value = payload.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " is required");
        long result = number.longValue();
        if (number.doubleValue() != result || result < minimum || result > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Host coordinator is closed");
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            activeRooms.values().forEach(HostRoom::close);
            activeRooms.keySet().forEach(rooms::closeRoom);
            activeRooms.clear();
        }
    }
}

