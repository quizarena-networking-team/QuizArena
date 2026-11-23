package com.quizarena.db;

import com.quizarena.quiz.ResultSnapshot;
import java.sql.SQLException;

@FunctionalInterface
public interface ResultDao {
    /** Must commit all session/results changes atomically before returning. */
    void save(ResultSnapshot snapshot) throws SQLException;
}
