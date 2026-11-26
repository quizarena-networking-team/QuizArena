package com.quizarena.security;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Creates the persistent session row paired with an in-memory room. */
public final class JdbcQuizSessionStore implements QuizSessionStore {
    private final DataSource source;

    public JdbcQuizSessionStore(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public long create(long hostId, String roomCode) throws SQLException {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO quiz_sessions (room_code, host_id, status)
                     VALUES (?, ?, 'LOBBY')
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, roomCode);
            statement.setLong(2, hostId);
            statement.setQueryTimeout(10);
            if (statement.executeUpdate() != 1) throw new SQLException("Session was not created");
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Session ID was not returned");
                return keys.getLong(1);
            }
        }
    }
}

