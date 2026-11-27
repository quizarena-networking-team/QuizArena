package com.quizarena.security.tls;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.AuthenticatedHost;
import com.quizarena.security.HostAccount;
import com.quizarena.security.HostAuthenticator;
import com.quizarena.security.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class TlsHostCommandHandlerTest {
    @Test
    void requiresLoginThenBindsVerifiedIdentityAndDelegatesCommands() throws Exception {
        char[] correct = "Correct-Horse-4827!".toCharArray();
        var hasher = new PasswordHasher();
        var password = hasher.hash(correct);
        var account = new HostAccount(42, "host-admin", password.salt(), password.hash(), password.iterations());
        var authenticator = new HostAuthenticator(
                username -> username.equals("host-admin") ? Optional.of(account) : Optional.empty(), hasher);
        var delegated = new ArrayList<AuthenticatedHost>();
        var handler = new TlsHostCommandHandler(authenticator,
                (request, session, host) -> delegated.add(host));
        var session = new FakeSession();

        handler.handle(message(CREATE_ROOM, "r1", Map.of()), session);
        assertResponse(session.last(), CREATE_ROOM_RESULT, "r1", "ERROR", "UNAUTHORIZED");

        handler.handle(message(HOST_LOGIN, "r2",
                Map.of("username", "host-admin", "password", "wrong-password")), session);
        assertResponse(session.last(), HOST_LOGIN_RESULT, "r2", "ERROR", "UNAUTHORIZED");

        handler.handle(message(HOST_LOGIN, "r3",
                Map.of("username", "host-admin", "password", new String(correct))), session);
        assertResponse(session.last(), HOST_LOGIN_RESULT, "r3", "OK", null);
        assertEquals("42", session.last().payload().get("hostId"));
        assertEquals(Optional.of(new AuthenticatedHost(42, "host-admin")), session.authenticatedHost());

        handler.handle(message(CREATE_ROOM, "r4", Map.of()), session);
        assertEquals(List.of(new AuthenticatedHost(42, "host-admin")), delegated);
    }

    @Test
    void validatesEnvelopeAndDoesNotExposeDatabaseFailures() throws Exception {
        var hasher = new PasswordHasher();
        var handler = new TlsHostCommandHandler(
                new HostAuthenticator(username -> { throw new java.sql.SQLException("database details"); }, hasher),
                (request, session, host) -> fail("must not delegate"));
        var session = new FakeSession();

        handler.handle(message(HOST_LOGIN, null, Map.of("username", "host-admin", "password", "password")), session);
        assertResponse(session.last(), HOST_LOGIN_RESULT, null, "ERROR", "BAD_REQUEST");

        handler.handle(message(HOST_LOGIN, "r2",
                Map.of("username", "host-admin", "password", "Correct-Horse-4827!")), session);
        assertResponse(session.last(), HOST_LOGIN_RESULT, "r2", "ERROR", "INTERNAL_ERROR");
        assertFalse(session.last().payload().toString().contains("database details"));
    }

    @Test
    void throttlesRepeatedFailedLoginsBeforeCallingTheDatabaseAgain() throws Exception {
        var lookups = new AtomicInteger();
        var handler = new TlsHostCommandHandler(
                new HostAuthenticator(username -> { lookups.incrementAndGet(); return Optional.empty(); },
                        new PasswordHasher()),
                (request, session, host) -> fail("must not delegate"));
        var session = new FakeSession();

        for (int attempt = 1; attempt <= LoginAttemptLimiter.MAX_FAILURES; attempt++) {
            handler.handle(message(HOST_LOGIN, "failed-" + attempt,
                    Map.of("username", "host-admin", "password", "Wrong-Password-4827!")), session);
            assertResponse(session.last(), HOST_LOGIN_RESULT, "failed-" + attempt,
                    "ERROR", "UNAUTHORIZED");
        }
        handler.handle(message(HOST_LOGIN, "blocked",
                Map.of("username", "host-admin", "password", "Wrong-Password-4827!")), session);

        assertEquals(LoginAttemptLimiter.MAX_FAILURES, lookups.get());
        assertEquals("Too many failed login attempts; try again later",
                session.last().payload().get("message"));
    }

    private static ProtocolMessage message(String type, String requestId, Map<String, Object> payload) {
        return new ProtocolMessage(type, requestId, null, null, null, payload);
    }

    private static void assertResponse(ProtocolMessage response, String type, String requestId,
                                       String status, String errorCode) {
        assertEquals(type, response.type());
        assertEquals(requestId, response.requestId());
        assertEquals(status, response.status());
        assertEquals(errorCode, response.errorCode());
    }

    private static final class FakeSession implements TlsSessionContext {
        private final List<ProtocolMessage> sent = new ArrayList<>();
        private AuthenticatedHost host;

        @Override public void send(ProtocolMessage message) { sent.add(message); }
        @Override public Optional<AuthenticatedHost> authenticatedHost() { return Optional.ofNullable(host); }
        @Override public boolean authenticate(AuthenticatedHost value) {
            if (host != null) return false;
            host = value;
            return true;
        }
        @Override public SocketAddress remoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public String protocol() { return "TLSv1.3"; }
        ProtocolMessage last() { return sent.getLast(); }
    }
}
