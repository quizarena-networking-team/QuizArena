package com.quizarena.server;

/** A request the room layer rejects. {@code code} is one of the shared protocol error codes. */
public final class RoomException extends RuntimeException {
    private final String code;

    public RoomException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
