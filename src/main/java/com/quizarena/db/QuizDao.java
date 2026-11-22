package com.quizarena.db;

import com.quizarena.quiz.Quiz;
import java.sql.SQLException;

@FunctionalInterface
public interface QuizDao {
    Quiz load(String quizId) throws SQLException;
}
