package com.quizarena.quiz;

import java.util.HashSet;
import java.util.List;

public record Quiz(String id, String title, List<Question> questions) {
    public Quiz {
        if (id == null || id.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("Quiz ID and title are required");
        }
        questions = List.copyOf(questions);
        if (questions.isEmpty() || questions.size() > 100) {
            throw new IllegalArgumentException("A quiz requires 1-100 questions");
        }
        var ids = new HashSet<String>();
        for (Question question : questions) {
            if (!ids.add(question.id())) throw new IllegalArgumentException("Duplicate question ID");
        }
    }
}
