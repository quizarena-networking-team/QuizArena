package com.quizarena.server;

import com.quizarena.protocol.MessageHandler;
import com.quizarena.protocol.ProtocolMessage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.quizarena.protocol.MessageTypes.*;

/**
 * Routes complete, decoded requests from the gameplay port to feature handlers.
 *
 * Feature modules register the request types they own (for example {@code PollRequestHandler}
 * registers SUBMIT_VOTE). Handlers run on worker threads, never on the selector thread, and
 * requests from one connection run in the order they arrived.
 *
 * Security rules enforced here, before any handler sees the request:
 * <ul>
 *   <li>Host commands (HOST_LOGIN, CREATE_ROOM, START_*, ...) are answered with UNAUTHORIZED.
 *       They are only valid on the authenticated TLS port (Member 5) and can never be registered here.</li>
 *   <li>Anything that is not a registered gameplay request gets BAD_REQUEST with no side effects.</li>
 *   <li>A handler that throws produces a generic INTERNAL_ERROR; details stay in the server log.</li>
 * </ul>
 */
public final class MessageDispatcher {

    /** Request types the plaintext gameplay port is allowed to route. */
    public static final Set<String> GAMEPLAY_REQUESTS = Set.of(
            PING, JOIN_ROOM, LEAVE_ROOM, SUBMIT_VOTE, SUBMIT_ANSWER, PRESS_BUZZER, GET_LEADERBOARD);

    /** Host-only commands. Valid solely on the TLS host-control channel. */
    public static final Set<String> HOST_COMMANDS = Set.of(
            HOST_LOGIN, CREATE_ROOM, START_POLL, CLOSE_POLL, START_QUIZ, NEXT_QUESTION, OPEN_BUZZER, FINISH_QUIZ);

    private static final Pattern TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,47}");
    private static final System.Logger LOG = System.getLogger(MessageDispatcher.class.getName());

    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();

    public void register(String type, MessageHandler handler) {
        if (HOST_COMMANDS.contains(type)) {
            throw new IllegalArgumentException(
                    type + " is a host command and may only be served on the authenticated TLS port");
        }
        if (!GAMEPLAY_REQUESTS.contains(type)) {
            throw new IllegalArgumentException(type + " is not a gameplay request type");
        }
        if (handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalStateException("A handler is already registered for " + type);
        }
    }

    public void register(Set<String> types, MessageHandler handler) {
        for (String type : types) {
            register(type, handler);
        }
    }

    public boolean isRegistered(String type) {
        return handlers.containsKey(type);
    }

    /** True if the type is shaped like a protocol constant; anything else is treated as garbage. */
    static boolean isWellFormedType(String type) {
        return type != null && TYPE_PATTERN.matcher(type).matches();
    }

    /** The reply type for a request type: PING -> PONG, GET_LEADERBOARD -> LEADERBOARD_RESULT, X -> X_RESULT. */
    static String replyTypeFor(String requestType) {
        return switch (requestType) {
            case PING -> PONG;
            case GET_LEADERBOARD -> LEADERBOARD_RESULT;
            default -> requestType + "_RESULT";
        };
    }

    /** Standard direct error reply that preserves the request's correlation fields. */
    static ProtocolMessage error(ProtocolMessage request, String errorCode, String message) {
        return new ProtocolMessage(replyTypeFor(request.type()), request.requestId(), request.roomCode(),
                "ERROR", errorCode, Map.of("message", message));
    }

    /** Runs on a worker thread. */
    void dispatch(ProtocolMessage request, SessionContext context) {
        if (HOST_COMMANDS.contains(request.type())) {
            context.reply(error(request, "UNAUTHORIZED", "Host commands require the authenticated host channel"));
            return;
        }
        if (request.status() != null || request.errorCode() != null) {
            context.reply(error(request, "BAD_REQUEST", "Clients may only send requests"));
            return;
        }
        MessageHandler handler = handlers.get(request.type());
        if (handler == null) {
            context.reply(error(request, "BAD_REQUEST", "Unsupported request type"));
            return;
        }
        try {
            handler.handle(request, context);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR,
                    "Handler failed for " + request.type() + " on " + context.connectionId(), failure);
            if (!context.hasReplied()) {
                context.reply(error(request, "INTERNAL_ERROR", "The server could not complete the request"));
            }
        }
    }
}
