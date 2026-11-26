package com.quizarena.security;

import java.sql.SQLException;

@FunctionalInterface
public interface QuizSessionStore {
    long create(long hostId, String roomCode) throws SQLException;
}

