package com.quizarena.poll;

import com.quizarena.protocol.MessageHandler;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.protocol.RequestContext;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.quizarena.protocol.MessageTypes.CLOSE_POLL;
import static com.quizarena.protocol.MessageTypes.CLOSE_POLL_RESULT;
import static com.quizarena.protocol.MessageTypes.START_POLL;
import static com.quizarena.protocol.MessageTypes.START_POLL_RESULT;
import static com.quizarena.protocol.MessageTypes.SUBMIT_VOTE;
import static com.quizarena.protocol.MessageTypes.SUBMIT_VOTE_RESULT;

/** Gameplay adapter. Register only SUBMIT_VOTE in the gameplay dispatcher. */
public final class PollRequestHandler implements MessageHandler {
    private static final System.Logger LOG = System.getLogger(PollRequestHandler.class.getName());
    private final PollService polls;

    public PollRequestHandler(PollService polls) {
        this.polls = Objects.requireNonNull(polls, "polls");
    }

    public Set<String> supportedTypes() {
        return Set.of(SUBMIT_VOTE);
    }

    @Override
    public void handle(ProtocolMessage request, RequestContext context) {
        ProtocolMessage response = switch (request.type()) {
            case SUBMIT_VOTE -> vote(request, context);
            case START_POLL -> error(START_POLL_RESULT, request, "UNAUTHORIZED");
            case CLOSE_POLL -> error(CLOSE_POLL_RESULT, request, "UNAUTHORIZED");
            default -> error(SUBMIT_VOTE_RESULT, request, "UNAUTHORIZED");
        };
        context.reply(response);
    }

    private ProtocolMessage vote(ProtocolMessage request, RequestContext context) {
        try {
            if (request.requestId() == null || request.requestId().isBlank()
                    || request.roomCode() == null || request.roomCode().isBlank()
                    || request.status() != null || request.errorCode() != null) {
                throw new PollException("BAD_REQUEST", "Invalid vote request envelope");
            }
            if (!polls.roomCode().equals(request.roomCode()) || !polls.roomCode().equals(context.roomCode())) {
                throw new PollException("ROOM_NOT_FOUND", "Connection is not joined to the requested room");
            }
            String pollId = text(request, "pollId");
            String optionId = text(request, "optionId");
            polls.submitVote(context.playerId(), pollId, optionId);
            return new ProtocolMessage(SUBMIT_VOTE_RESULT, request.requestId(), request.roomCode(), "OK", null,
                    Map.of("accepted", true, "pollId", pollId, "optionId", optionId));
        } catch (PollException failure) {
            return error(SUBMIT_VOTE_RESULT, request, failure.code());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not process poll vote", failure);
            return error(SUBMIT_VOTE_RESULT, request, "INTERNAL_ERROR");
        }
    }

    private static String text(ProtocolMessage request, String field) {
        if (request.payload().get(field) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new PollException("BAD_REQUEST", "Missing or invalid " + field);
    }

    private static ProtocolMessage error(String type, ProtocolMessage request, String code) {
        return new ProtocolMessage(type, request.requestId(), request.roomCode(), "ERROR", code, Map.of());
    }
}
