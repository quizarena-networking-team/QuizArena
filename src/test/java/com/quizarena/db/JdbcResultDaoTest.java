package com.quizarena.db;

import com.quizarena.quiz.ResultSnapshot;
import com.quizarena.quiz.ScoreService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Transaction fault-injection tests; live MySQL compatibility is covered by JdbcMySqlTest. */
class JdbcResultDaoTest {
    static ResultSnapshot snapshot() {
        return new ResultSnapshot(1, "ABC123", "7", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:01:00Z"),
                List.of(new ScoreService.Entry("player", "Player", 150, 2, 2, 1)));
    }

    @Test void commitOccursAfterBatchAndSessionUpdateAndResourcesClose() throws Exception {
        var db = new FakeJdbc();
        new JdbcResultDao(db.source()).save(snapshot());
        assertEquals(List.of("begin", "lock", "batch", "update", "commit", "restore", "close"), db.actions);
        assertEquals(3, db.statementsClosed);
        assertEquals(1, db.resultSetsClosed);
    }

    @Test void failedBatchRollsBackAndNeverUpdatesSessionOrCommits() {
        var db = new FakeJdbc(); db.failBatch = true;
        assertThrows(SQLException.class, () -> new JdbcResultDao(db.source()).save(snapshot()));
        assertEquals(List.of("begin", "lock", "batch", "rollback", "restore", "close"), db.actions);
        assertEquals(2, db.statementsClosed);
    }

    @Test void missingSessionUpdateRollsBackInsertedResults() {
        var db = new FakeJdbc(); db.updateCount = 0;
        assertThrows(SQLException.class, () -> new JdbcResultDao(db.source()).save(snapshot()));
        assertEquals(List.of("begin", "lock", "batch", "update", "rollback", "restore", "close"), db.actions);
    }

    @Test void alreadyCommittedMatchingSnapshotDoesNotInsertAgain() throws Exception {
        var db = new FakeJdbc(); db.status = "FINISHED";
        db.persisted = List.of(Map.of("player_key", "player", "player_name", "Player", "score", 150,
                "correct_count", 2, "answered_count", 2, "final_rank", 1));
        new JdbcResultDao(db.source()).save(snapshot());
        assertEquals(List.of("begin", "lock", "verify", "commit", "restore", "close"), db.actions);
    }

    @Test void committedButDifferentResultsAreNotOverwritten() {
        var db = new FakeJdbc(); db.status = "FINISHED";
        assertThrows(SQLException.class, () -> new JdbcResultDao(db.source()).save(snapshot()));
        assertEquals(List.of("begin", "lock", "verify", "rollback", "restore", "close"), db.actions);
    }

    @Test void sessionForDifferentQuizIsRejectedBeforeInserting() {
        var db = new FakeJdbc(); db.quizId = "other";
        assertThrows(SQLException.class, () -> new JdbcResultDao(db.source()).save(snapshot()));
        assertEquals(List.of("begin", "lock", "rollback", "restore", "close"), db.actions);
    }

    static final class FakeJdbc {
        final List<String> actions = new ArrayList<>();
        String status = "RUNNING";
        String quizId = "7";
        boolean failBatch;
        int updateCount = 1;
        int statementsClosed;
        int resultSetsClosed;
        List<Map<String, Object>> persisted = List.of();

        DataSource source() {
            return proxy(DataSource.class, (object, method, args) -> {
                if (method.getName().equals("getConnection")) return connection();
                throw new UnsupportedOperationException(method.getName());
            });
        }

        Connection connection() {
            return proxy(Connection.class, (object, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit" -> { actions.add((Boolean) args[0] ? "restore" : "begin"); yield null; }
                case "prepareStatement" -> statement((String) args[0]);
                case "commit", "rollback", "close" -> { actions.add(method.getName()); yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        PreparedStatement statement(String sql) {
            return proxy(PreparedStatement.class, (object, method, args) -> switch (method.getName()) {
                case "setString", "setLong", "setInt", "setTimestamp", "setQueryTimeout", "addBatch" -> null;
                case "executeQuery" -> {
                    if (sql.contains("FOR UPDATE")) {
                        actions.add("lock");
                        var row = new HashMap<String, Object>();
                        row.put("status", status); row.put("quiz_id", quizId);
                        yield rows(List.of(row));
                    }
                    actions.add("verify");
                    yield rows(persisted);
                }
                case "executeBatch" -> {
                    actions.add("batch");
                    if (failBatch) throw new SQLException("Injected batch failure");
                    yield new int[] { 1 };
                }
                case "executeUpdate" -> { actions.add("update"); yield updateCount; }
                case "close" -> { statementsClosed++; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        ResultSet rows(List<Map<String, Object>> rows) {
            int[] index = { -1 };
            return proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
                case "next" -> ++index[0] < rows.size();
                case "getString", "getInt" -> rows.get(index[0]).get(args[0]);
                case "close" -> { resultSetsClosed++; yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }
}
