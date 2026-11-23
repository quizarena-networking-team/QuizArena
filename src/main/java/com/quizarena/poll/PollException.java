package com.quizarena.poll;

/** An expected rejection carrying a shared protocol error code for server adapters. */
public final class PollException extends RuntimeException {
    private final String code;

    PollException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
