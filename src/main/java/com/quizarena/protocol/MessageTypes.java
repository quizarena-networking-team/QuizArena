package com.quizarena.protocol;

/**
 * Shared network message names.
 * Do not create different names in individual modules.
 */
public final class MessageTypes {

    private MessageTypes() {
    }

    // TCP gameplay
    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String JOIN_ROOM = "JOIN_ROOM";
    public static final String LEAVE_ROOM = "LEAVE_ROOM";
    public static final String SUBMIT_VOTE = "SUBMIT_VOTE";
    public static final String SUBMIT_ANSWER = "SUBMIT_ANSWER";
    public static final String PRESS_BUZZER = "PRESS_BUZZER";
    public static final String GET_LEADERBOARD = "GET_LEADERBOARD";

    // TLS host commands
    public static final String HOST_LOGIN = "HOST_LOGIN";
    public static final String CREATE_ROOM = "CREATE_ROOM";
    public static final String START_POLL = "START_POLL";
    public static final String CLOSE_POLL = "CLOSE_POLL";
    public static final String START_QUIZ = "START_QUIZ";
    public static final String NEXT_QUESTION = "NEXT_QUESTION";
    public static final String OPEN_BUZZER = "OPEN_BUZZER";
    public static final String FINISH_QUIZ = "FINISH_QUIZ";

    // Server events
    public static final String LOBBY_UPDATED = "LOBBY_UPDATED";
    public static final String POLL_STARTED = "POLL_STARTED";
    public static final String POLL_RESULTS = "POLL_RESULTS";
    public static final String POLL_CLOSED = "POLL_CLOSED";
    public static final String QUESTION_STARTED = "QUESTION_STARTED";
    public static final String QUESTION_ENDED = "QUESTION_ENDED";
    public static final String BUZZER_OPENED = "BUZZER_OPENED";
    public static final String BUZZER_WINNER = "BUZZER_WINNER";
    public static final String LEADERBOARD_UPDATED = "LEADERBOARD_UPDATED";
    public static final String QUIZ_FINISHED = "QUIZ_FINISHED";
    public static final String ROOM_CLOSED = "ROOM_CLOSED";
}
