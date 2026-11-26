package com.quizarena.security.tls;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.AuthenticatedHost;
import com.quizarena.security.HostAuthenticator;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import static com.quizarena.protocol.MessageTypes.*;

/** Enforces login-before-command and binds the verified host identity to one TLS connection. */
public final class TlsHostCommandHandler implements TlsRequestHandler {
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final int MAX_USERNAME_LENGTH = 64;
    private static final int MAX_PASSWORD_LENGTH = 256;

    private final HostAuthenticator authenticator;
    private final AuthenticatedTlsCommandHandler authenticatedCommands;

    public TlsHostCommandHandler(HostAuthenticator authenticator,
                                 AuthenticatedTlsCommandHandler authenticatedCommands) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.authenticatedCommands = Objects.requireNonNull(authenticatedCommands, "authenticatedCommands");
    }

    @Override
    public void handle(ProtocolMessage request, TlsSessionContext session) throws Exception {
        if (!validRequestId(request.requestId())) {
            session.send(error(responseType(request.type()), request.requestId(), request.roomCode(),
                    "BAD_REQUEST", "A valid requestId is required"));
            return;
        }
        if (HOST_LOGIN.equals(request.type())) {
            login(request, session);
            return;
        }
        var host = session.authenticatedHost();
        if (host.isEmpty()) {
            session.send(error(responseType(request.type()), request.requestId(), request.roomCode(),
                    "UNAUTHORIZED", "Host login is required"));
            return;
        }
        if (!isHostCommand(request.type())) {
            session.send(error(responseType(request.type()), request.requestId(), request.roomCode(),
                    "BAD_REQUEST", "Unsupported TLS command"));
            return;
        }
        authenticatedCommands.handle(request, session, host.orElseThrow());
    }

    private void login(ProtocolMessage request, TlsSessionContext session) throws java.io.IOException {
        if (session.authenticatedHost().isPresent()) {
            session.send(error(HOST_LOGIN_RESULT, request.requestId(), null,
                    "BAD_REQUEST", "This TLS session is already authenticated"));
            return;
        }
        Object usernameValue = request.payload().get("username");
        Object passwordValue = request.payload().get("password");
        if (!(usernameValue instanceof String username) || username.isBlank()
                || username.length() > MAX_USERNAME_LENGTH
                || !(passwordValue instanceof String password) || password.isEmpty()
                || password.length() > MAX_PASSWORD_LENGTH) {
            session.send(error(HOST_LOGIN_RESULT, request.requestId(), null,
                    "BAD_REQUEST", "Username and password are required"));
            return;
        }

        char[] passwordCharacters = password.toCharArray();
        try {
            var verified = authenticator.authenticate(username, passwordCharacters);
            if (verified.isEmpty() || !session.authenticate(verified.orElseThrow())) {
                session.send(error(HOST_LOGIN_RESULT, request.requestId(), null,
                        "UNAUTHORIZED", "Invalid host credentials"));
                return;
            }
            AuthenticatedHost host = verified.orElseThrow();
            session.send(new ProtocolMessage(HOST_LOGIN_RESULT, request.requestId(), null,
                    "OK", null, Map.of("hostId", Long.toString(host.id()), "username", host.username())));
        } catch (SQLException failure) {
            session.send(error(HOST_LOGIN_RESULT, request.requestId(), null,
                    "INTERNAL_ERROR", "Host authentication is temporarily unavailable"));
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    private static boolean validRequestId(String requestId) {
        return requestId != null && !requestId.isBlank() && requestId.length() <= MAX_REQUEST_ID_LENGTH;
    }

    private static boolean isHostCommand(String type) {
        return switch (type) {
            case CREATE_ROOM, START_POLL, CLOSE_POLL, START_QUIZ, NEXT_QUESTION,
                    OPEN_BUZZER, FINISH_QUIZ -> true;
            default -> false;
        };
    }

    private static String responseType(String requestType) {
        return switch (requestType) {
            case HOST_LOGIN -> HOST_LOGIN_RESULT;
            case CREATE_ROOM -> CREATE_ROOM_RESULT;
            case START_POLL -> START_POLL_RESULT;
            case CLOSE_POLL -> CLOSE_POLL_RESULT;
            case START_QUIZ -> START_QUIZ_RESULT;
            case NEXT_QUESTION -> NEXT_QUESTION_RESULT;
            case OPEN_BUZZER -> OPEN_BUZZER_RESULT;
            case FINISH_QUIZ -> FINISH_QUIZ_RESULT;
            default -> requestType == null || requestType.isBlank() ? "ERROR" : requestType;
        };
    }

    private static ProtocolMessage error(String type, String requestId, String roomCode,
                                         String code, String message) {
        return new ProtocolMessage(type, requestId, roomCode, "ERROR", code, Map.of("message", message));
    }
}

