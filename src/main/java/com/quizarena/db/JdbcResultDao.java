package com.quizarena.db;

import com.quizarena.quiz.ResultSnapshot;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Saves the existing M5-created session and all final result rows in one transaction. */
public final class JdbcResultDao implements ResultDao {
    private final DataSource source;

    public JdbcResultDao(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public void save(ResultSnapshot snapshot) throws SQLException {
        try (var connection = source.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String status = lockSession(connection, snapshot);
                if ("FINISHED".equals(status)) {
                    verifyExisting(connection, snapshot);
                } else {
                    if (!"LOBBY".equals(status) && !"RUNNING".equals(status)) {
                        throw new SQLException("Session cannot be finalized from " + status);
                    }
                    insertResults(connection, snapshot);
                    try (var statement = connection.prepareStatement("""
                            UPDATE quiz_sessions SET quiz_id = ?, status = 'FINISHED', started_at = ?, ended_at = ?
                            WHERE id = ? AND room_code = ?
                            """)) {
                        statement.setString(1, snapshot.quizId());
                        statement.setTimestamp(2, Timestamp.from(snapshot.startedAt()));
                        statement.setTimestamp(3, Timestamp.from(snapshot.endedAt()));
                        statement.setLong(4, snapshot.sessionId());
                        statement.setString(5, snapshot.roomCode());
                        statement.setQueryTimeout(10);
                        if (statement.executeUpdate() != 1) throw new SQLException("Session update failed");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private String lockSession(Connection connection, ResultSnapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT status, quiz_id FROM quiz_sessions WHERE id = ? AND room_code = ? FOR UPDATE")) {
            statement.setLong(1, snapshot.sessionId());
            statement.setString(2, snapshot.roomCode());
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Session does not exist for this room");
                String existingQuizId = rows.getString("quiz_id");
                if (existingQuizId != null && !snapshot.quizId().equals(existingQuizId)) {
                    throw new SQLException("Session belongs to a different quiz");
                }
                return rows.getString("status");
            }
        }
    }

    private void insertResults(Connection connection, ResultSnapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO quiz_results
                  (session_id, player_key, player_name, score, correct_count, answered_count, final_rank)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setQueryTimeout(10);
            for (var entry : snapshot.entries()) {
                statement.setLong(1, snapshot.sessionId());
                statement.setString(2, entry.playerId());
                statement.setString(3, entry.playerName());
                statement.setInt(4, entry.score());
                statement.setInt(5, entry.correctCount());
                statement.setInt(6, entry.answeredCount());
                statement.setInt(7, entry.rank());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Handles a retry after a successful commit whose acknowledgement was lost. No overwrites. */
    private void verifyExisting(Connection connection, ResultSnapshot snapshot) throws SQLException {
        Map<String, com.quizarena.quiz.ScoreService.Entry> expected = new HashMap<>();
        snapshot.entries().forEach(entry -> expected.put(entry.playerId(), entry));
        try (var statement = connection.prepareStatement("""
                SELECT player_key, player_name, score, correct_count, answered_count, final_rank
                FROM quiz_results WHERE session_id = ?
                """)) {
            statement.setLong(1, snapshot.sessionId());
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var entry = expected.remove(rows.getString("player_key"));
                    if (entry == null || !entry.playerName().equals(rows.getString("player_name"))
                            || entry.score() != rows.getInt("score")
                            || entry.correctCount() != rows.getInt("correct_count")
                            || entry.answeredCount() != rows.getInt("answered_count")
                            || entry.rank() != rows.getInt("final_rank")) {
                        throw new SQLException("Persisted results differ from the retry snapshot");
                    }
                }
            }
        }
        if (!expected.isEmpty()) throw new SQLException("Persisted results are incomplete");
    }
}
