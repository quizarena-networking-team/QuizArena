package com.quizarena.db;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.quizarena.quiz.ResultSnapshot;
import com.quizarena.quiz.ScoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: point at a disposable database initialized with M5's blueprint schema. */
@EnabledIfEnvironmentVariable(named = "QUIZ_M3_TEST_DB_URL", matches = ".+")
class JdbcMySqlTest {
    @Test void loadsQuestionsSavesResultsAcrossConnectionsAndRollsBackFailedSave() throws Exception {
        var source = new MysqlDataSource();
        source.setURL(System.getenv("QUIZ_M3_TEST_DB_URL"));
        source.setUser(System.getenv("QUIZ_M3_TEST_DB_USER"));
        source.setPassword(System.getenv("QUIZ_M3_TEST_DB_PASSWORD"));
        long hostId = 0, quizId = 0, sessionId = 0;
        String roomCode = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (var setup = source.getConnection()) {
            try {
                hostId = insert(setup, """
                        INSERT INTO hosts (username, password_salt, password_hash, password_iterations)
                        VALUES (?, RANDOM_BYTES(16), RANDOM_BYTES(32), 100000)
                        """, "m3-test-" + UUID.randomUUID());
                quizId = insert(setup, "INSERT INTO quizzes (title, category, created_by) VALUES (?, ?, ?)",
                        "M3 integration test", "test", hostId);
                for (int i = 1; i <= 3; i++) {
                    insert(setup, """
                            INSERT INTO questions (quiz_id, question_order, question_text,
                            option_a, option_b, option_c, option_d, correct_option, points, duration_seconds)
                            VALUES (?, ?, ?, 'Alpha', 'Beta', 'Gamma', 'Delta', 'A', 100, 20)
                            """, quizId, i, "Question " + i);
                }
                sessionId = insert(setup, """
                        INSERT INTO quiz_sessions (room_code, host_id, quiz_id, status) VALUES (?, ?, ?, 'RUNNING')
                        """, roomCode, hostId, quizId);
                var loaded = new JdbcQuizDao(source).load(Long.toString(quizId));
                assertEquals(3, loaded.questions().size());
                assertEquals("Question 1", loaded.questions().getFirst().text());
                assertEquals("A", loaded.questions().getFirst().correctOption());

                var entry = new ScoreService.Entry(UUID.randomUUID().toString(), "Player", 150, 2, 2, 1);
                var now = Instant.now();
                // Duplicate player violates the unique key after the first insert: the entire save must roll back.
                var invalid = new ResultSnapshot(sessionId, roomCode, Long.toString(quizId), now, now,
                        List.of(entry, entry));
                assertThrows(SQLException.class, () -> new JdbcResultDao(source).save(invalid));
                try (var check = source.getConnection(); var statement = check.prepareStatement(
                        "SELECT status, (SELECT COUNT(*) FROM quiz_results WHERE session_id = ?) AS total FROM quiz_sessions WHERE id = ?")) {
                    statement.setLong(1, sessionId); statement.setLong(2, sessionId);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next()); assertEquals("RUNNING", rows.getString("status"));
                        assertEquals(0, rows.getInt("total"));
                    }
                }
                var snapshot = new ResultSnapshot(sessionId, roomCode, Long.toString(quizId), now, now, List.of(entry));
                new JdbcResultDao(source).save(snapshot);
                // A new DAO and new connections prove data is committed, not retained in service memory.
                new JdbcResultDao(source).save(snapshot);
                try (var check = source.getConnection(); var statement = check.prepareStatement(
                        "SELECT score, final_rank FROM quiz_results WHERE session_id = ?")) {
                    statement.setLong(1, sessionId);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next()); assertEquals(150, rows.getInt("score"));
                        assertEquals(1, rows.getInt("final_rank")); assertFalse(rows.next());
                    }
                }
            } finally {
                delete(setup, "DELETE FROM quiz_results WHERE session_id = ?", sessionId);
                delete(setup, "DELETE FROM quiz_sessions WHERE id = ?", sessionId);
                delete(setup, "DELETE FROM questions WHERE quiz_id = ?", quizId);
                delete(setup, "DELETE FROM quizzes WHERE id = ?", quizId);
                delete(setup, "DELETE FROM hosts WHERE id = ?", hostId);
            }
        }
    }

    private long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated ID");
                return keys.getLong(1);
            }
        }
    }

    private void delete(Connection connection, String sql, long id) throws SQLException {
        if (id == 0) return;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id); statement.executeUpdate();
        }
    }
}
