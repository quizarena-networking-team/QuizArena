package com.quizarena.quiz;

/** Safe protocol failure; internal exception details must not be sent to clients. */
public final class QuizException extends RuntimeException {
    private final String code;

    public QuizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
