package com.quizarena.security;

import javax.sql.DataSource;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Loads host authentication material with a prepared, bounded database query. */
public final class JdbcHostRepository implements HostRepository {
    private final DataSource source;

    public JdbcHostRepository(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Optional<HostAccount> findByUsername(String username) throws SQLException {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, username, password_salt, password_hash, password_iterations
                     FROM hosts
                     WHERE username = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, username);
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new HostAccount(
                        rows.getLong("id"),
                        rows.getString("username"),
                        rows.getBytes("password_salt"),
                        rows.getBytes("password_hash"),
                        rows.getInt("password_iterations")));
            }
        }
    }

    /** Creates a host from an already-hashed password and returns its generated ID. */
    public long create(String username, PasswordHasher.PasswordRecord password) throws SQLException {
        Objects.requireNonNull(password, "password");
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO hosts (username, password_salt, password_hash, password_iterations)
                     VALUES (?, ?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setBytes(2, password.salt());
            statement.setBytes(3, password.hash());
            statement.setInt(4, password.iterations());
            statement.setQueryTimeout(10);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Host account was not created");
            }
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Host account ID was not returned");
                }
                return keys.getLong(1);
            }
        }
    }
}

