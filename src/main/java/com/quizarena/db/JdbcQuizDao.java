package com.quizarena.db;

import com.quizarena.quiz.Question;
import com.quizarena.quiz.Quiz;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** Uses M5's DataSource and blueprint tables; does not own credentials or schema creation. */
public final class JdbcQuizDao implements QuizDao {
    private final DataSource source;

    public JdbcQuizDao(DataSource source) { this.source = Objects.requireNonNull(source); }

    @Override public Quiz load(String quizId) throws SQLException {
        try (var connection = source.getConnection()) {
            // One query ensures metadata and ordered questions come from the same statement snapshot.
            try (var statement = connection.prepareStatement("""
                    SELECT q.id, q.title, x.id AS question_id, x.question_text,
                           x.option_a, x.option_b, x.option_c, x.option_d,
                           x.correct_option, x.points, x.duration_seconds
                    FROM quizzes q JOIN questions x ON x.quiz_id = q.id
                    WHERE q.id = ? ORDER BY x.question_order, x.id
                    """)) {
                statement.setString(1, quizId);
                statement.setQueryTimeout(10);
                try (var rows = statement.executeQuery()) {
                    var questions = new ArrayList<Question>();
                    String title = null;
                    String id = null;
                    while (rows.next()) {
                        id = rows.getString("id");
                        title = rows.getString("title");
                        questions.add(new Question(rows.getString("question_id"), rows.getString("question_text"),
                                Map.of("A", rows.getString("option_a"), "B", rows.getString("option_b"),
                                        "C", rows.getString("option_c"), "D", rows.getString("option_d")),
                                rows.getString("correct_option"), rows.getInt("points"),
                                Duration.ofSeconds(rows.getInt("duration_seconds"))));
                        if (questions.size() > 100) throw new SQLException("Quiz exceeds 100-question limit");
                    }
                    if (questions.isEmpty()) throw new SQLException("Quiz missing or contains no questions");
                    return new Quiz(id, title, questions);
                }
            }
        }
    }
}
