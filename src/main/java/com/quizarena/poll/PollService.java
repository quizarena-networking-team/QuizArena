package com.quizarena.poll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.quiz.GameEventSink;
import com.quizarena.quiz.PlayerDirectory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.quizarena.protocol.MessageTypes.POLL_CLOSED;
import static com.quizarena.protocol.MessageTypes.POLL_RESULTS;
import static com.quizarena.protocol.MessageTypes.POLL_STARTED;

/** Owns the current poll for one room. Adapters must provide non-blocking lookup and enqueue operations. */
public final class PollService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(PollService.class.getName());

    private final Object lock = new Object();
    private final String roomCode;
    private final PlayerDirectory players;
    private final GameEventSink events;
    private Poll current;
    private boolean roomClosed;

    public PollService(String roomCode, PlayerDirectory players, GameEventSink events) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("Room code is required");
        }
        this.roomCode = roomCode;
        this.players = Objects.requireNonNull(players, "players");
        this.events = Objects.requireNonNull(events, "events");
    }

    public String roomCode() {
        return roomCode;
    }

    /** Trusted server entry point, called after the host is authorized for this room. */
    public PollSnapshot startPoll(String question, Map<String, String> options) {
        if (question == null || question.isBlank() || options == null) {
            throw new PollException("BAD_REQUEST", "A question and at least two options are required");
        }
        Map<String, String> definition = new HashMap<>();
        options.forEach((id, label) -> {
            if (id == null || id.isBlank() || label == null || label.isBlank()) {
                throw new PollException("BAD_REQUEST", "Poll option IDs and labels are required");
            }
            definition.put(id, label);
        });
        if (definition.size() < 2) {
            throw new PollException("BAD_REQUEST", "A poll requires at least two options");
        }

        var next = new Poll(question, definition);
        PollSnapshot initial = next.snapshot();
        ProtocolMessage started = event(POLL_STARTED, Map.of("pollId", initial.pollId(),
                "question", initial.question(), "options", initial.options(), "counts", initial.counts()));
        validateEventSizes(started, initial);

        synchronized (lock) {
            ensureRoomOpen();
            if (current != null && current.isOpen()) {
                throw new PollException("BAD_REQUEST", "A poll is already open");
            }
            current = next;
            enqueue(started);
            return initial;
        }
    }

    /** Uses a player identity obtained from the server's gameplay session. */
    public void submitVote(String playerId, String pollId, String optionId) {
        synchronized (lock) {
            Poll poll = requirePoll(pollId);
            if (!poll.isOpen()) {
                throw new PollException("POLL_CLOSED", "Poll is closed");
            }
            if (playerId == null || playerId.isBlank() || players.find(roomCode, playerId).isEmpty()) {
                throw new PollException("INVALID_PLAYER", "Player is not in the room");
            }
            poll.recordVote(playerId, optionId);
            enqueue(event(POLL_RESULTS, Map.of("pollId", poll.id(), "counts", poll.countsSnapshot())));
        }
    }

    /** Trusted host entry point. A repeat close of the current poll returns the same final results. */
    public PollSnapshot closePoll(String pollId) {
        synchronized (lock) {
            Poll poll = requirePoll(pollId);
            if (poll.isOpen()) {
                poll.close();
                enqueue(event(POLL_CLOSED, Map.of("pollId", poll.id(), "counts", poll.countsSnapshot())));
            }
            return poll.snapshot();
        }
    }

    /** Returns the open poll or the most recently closed poll until replacement or room cleanup. */
    public Optional<PollSnapshot> currentPoll() {
        synchronized (lock) {
            return current == null ? Optional.empty() : Optional.of(current.snapshot());
        }
    }

    /** Called when the owning room closes. The room service owns the ROOM_CLOSED event. */
    @Override
    public void close() {
        synchronized (lock) {
            roomClosed = true;
            current = null;
        }
    }

    private Poll requirePoll(String pollId) {
        ensureRoomOpen();
        if (pollId == null || pollId.isBlank()) {
            throw new PollException("BAD_REQUEST", "Poll ID is required");
        }
        if (current == null) {
            throw new PollException("POLL_CLOSED", "No poll has been started");
        }
        if (!current.id().equals(pollId)) {
            throw new PollException("BAD_REQUEST", "Unknown poll ID");
        }
        return current;
    }

    private void ensureRoomOpen() {
        if (roomClosed) {
            throw new PollException("ROOM_CLOSED", "Room is closed");
        }
    }

    private ProtocolMessage event(String type, Map<String, Object> payload) {
        return new ProtocolMessage(type, null, roomCode, null, null, payload);
    }

    private void validateEventSizes(ProtocolMessage started, PollSnapshot initial) {
        try {
            MessageCodec.encodeLine(started);
            // Reserve enough bytes for every count to grow while the poll is open.
            Map<String, Integer> maxCounts = new HashMap<>();
            initial.options().keySet().forEach(id -> maxCounts.put(id, Integer.MAX_VALUE));
            MessageCodec.encodeLine(event(POLL_RESULTS, Map.of("pollId", initial.pollId(), "counts", maxCounts)));
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new PollException("BAD_REQUEST", "Poll details cannot be encoded within the gameplay frame limit");
        }
    }

    private void enqueue(ProtocolMessage event) {
        try {
            // Enqueue under the state lock to preserve transition order. The adapter owns network delivery.
            events.broadcast(event);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not enqueue poll event " + event.type(), failure);
        }
    }
}
