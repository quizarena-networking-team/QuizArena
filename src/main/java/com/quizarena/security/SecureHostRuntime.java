package com.quizarena.security;

import com.quizarena.db.DataSourceFactory;
import com.quizarena.db.DatabaseConfig;
import com.quizarena.db.JdbcQuizDao;
import com.quizarena.db.JdbcResultDao;
import com.quizarena.security.tls.SslContextFactory;
import com.quizarena.security.tls.TlsHostCommandHandler;
import com.quizarena.security.tls.TlsHostServer;
import com.quizarena.security.tls.TlsServerConfig;
import com.quizarena.server.MessageDispatcher;
import com.quizarena.server.RoomRegistry;

import java.io.IOException;
import java.sql.SQLException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fully wired secure host-control runtime: MySQL authentication, TLS, and gameplay routing. */
public final class SecureHostRuntime implements AutoCloseable {
    private final HostCommandCoordinator coordinator;
    private final TlsHostServer server;

    private SecureHostRuntime(HostCommandCoordinator coordinator, TlsHostServer server) {
        this.coordinator = coordinator;
        this.server = server;
    }

    public static SecureHostRuntime start(Map<String, String> env, RoomRegistry rooms,
                                          MessageDispatcher dispatcher) throws IOException, SQLException {
        Objects.requireNonNull(env, "env");
        var source = DataSourceFactory.create(DatabaseConfig.fromEnv(env));
        // Fail at startup, rather than accepting TLS connections with an unusable database.
        try (var connection = source.getConnection()) {
            if (!connection.isValid(5)) throw new SQLException("MySQL connection validation failed");
        }

        var coordinator = new HostCommandCoordinator(rooms, new JdbcQuizSessionStore(source),
                new JdbcQuizDao(source), new JdbcResultDao(source));
        dispatcher.register(coordinator.gameplayTypes(), coordinator.gameplayHandler());

        var authenticator = new HostAuthenticator(new JdbcHostRepository(source), new PasswordHasher());
        var handler = new TlsHostCommandHandler(authenticator, coordinator);
        var tlsConfig = TlsServerConfig.fromEnv(env);
        TlsHostServer tlsServer;
        try {
            tlsServer = new TlsHostServer(tlsConfig, SslContextFactory.serverContext(tlsConfig), handler);
        } catch (GeneralSecurityException failure) {
            coordinator.close();
            throw new IOException("Could not initialize the TLS key material", failure);
        }
        try {
            tlsServer.start();
            return new SecureHostRuntime(coordinator, tlsServer);
        } catch (IOException | RuntimeException failure) {
            coordinator.close();
            throw failure;
        }
    }

    public static boolean isEnabled(Map<String, String> env) {
        String value = env.get("QUIZ_TLS_ENABLED");
        if (value == null || value.isBlank()) return false;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException(
                    "QUIZ_TLS_ENABLED must be true/false, yes/no, or 1/0");
        };
    }

    public int boundPort() {
        return server.boundPort();
    }

    @Override public void close() {
        server.close();
        coordinator.close();
    }
}
