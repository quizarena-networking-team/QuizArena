package com.quizarena.protocol;

/**
 * Central definitions for all QuizArena message types.
 *
 * All networking modules must use these constants
 * instead of hardcoding message type strings.
 */
public final class MessageTypes {

    private MessageTypes() {
        // Utility class - no instances allowed.
    }

    // ========================================
    // TCP GAMEPLAY REQUESTS
    // ========================================

    public static final String PING = "PING";

    public static final String JOIN_ROOM = "JOIN_ROOM";

    public static final String LEAVE_ROOM = "LEAVE_ROOM";

    public static final String SUBMIT_VOTE = "SUBMIT_VOTE";

    public static final String SUBMIT_ANSWER = "SUBMIT_ANSWER";

    public static final String PRESS_BUZZER = "PRESS_BUZZER";

    public static final String GET_LEADERBOARD = "GET_LEADERBOARD";


    // ========================================
    // TCP GAMEPLAY RESPONSES
    // ========================================

    public static final String PONG = "PONG";

    public static final String JOIN_ROOM_RESULT =
            "JOIN_ROOM_RESULT";

    public static final String LEAVE_ROOM_RESULT =
            "LEAVE_ROOM_RESULT";

    public static final String SUBMIT_VOTE_RESULT =
            "SUBMIT_VOTE_RESULT";

    public static final String SUBMIT_ANSWER_RESULT =
            "SUBMIT_ANSWER_RESULT";

    public static final String PRESS_BUZZER_RESULT =
            "PRESS_BUZZER_RESULT";

    public static final String LEADERBOARD_RESULT =
            "LEADERBOARD_RESULT";


    // ========================================
    // TLS SECURE HOST REQUESTS
    // ========================================

    public static final String HOST_LOGIN = "HOST_LOGIN";

    public static final String CREATE_ROOM = "CREATE_ROOM";

    public static final String START_POLL = "START_POLL";

    public static final String CLOSE_POLL = "CLOSE_POLL";

    public static final String START_QUIZ = "START_QUIZ";

    public static final String NEXT_QUESTION = "NEXT_QUESTION";

    public static final String OPEN_BUZZER = "OPEN_BUZZER";

    public static final String FINISH_QUIZ = "FINISH_QUIZ";


    // ========================================
    // TLS SECURE HOST RESPONSES
    // ========================================

    public static final String HOST_LOGIN_RESULT =
            "HOST_LOGIN_RESULT";

    public static final String CREATE_ROOM_RESULT =
            "CREATE_ROOM_RESULT";

    public static final String START_POLL_RESULT =
            "START_POLL_RESULT";

    public static final String CLOSE_POLL_RESULT =
            "CLOSE_POLL_RESULT";

    public static final String START_QUIZ_RESULT =
            "START_QUIZ_RESULT";

    public static final String NEXT_QUESTION_RESULT =
            "NEXT_QUESTION_RESULT";

    public static final String OPEN_BUZZER_RESULT =
            "OPEN_BUZZER_RESULT";

    public static final String FINISH_QUIZ_RESULT =
            "FINISH_QUIZ_RESULT";


    // ========================================
    // SERVER PUSH EVENTS
    // ========================================

    public static final String LOBBY_UPDATED =
            "LOBBY_UPDATED";

    public static final String POLL_STARTED =
            "POLL_STARTED";

    public static final String POLL_RESULTS =
            "POLL_RESULTS";

    public static final String POLL_CLOSED =
            "POLL_CLOSED";

    public static final String QUESTION_STARTED =
            "QUESTION_STARTED";

    public static final String QUESTION_ENDED =
            "QUESTION_ENDED";

    public static final String BUZZER_OPENED =
            "BUZZER_OPENED";

    public static final String BUZZER_WINNER =
            "BUZZER_WINNER";

    public static final String LEADERBOARD_UPDATED =
            "LEADERBOARD_UPDATED";

    public static final String QUIZ_FINISHED =
            "QUIZ_FINISHED";

    public static final String ROOM_CLOSED =
            "ROOM_CLOSED";


    // ========================================
    // UDP / MULTICAST MESSAGE TYPES
    // ========================================

    public static final String DISCOVER = "DISCOVER";

    public static final String DISCOVER_RESULT =
            "DISCOVER_RESULT";

    public static final String HEARTBEAT = "HEARTBEAT";

    public static final String ANNOUNCEMENT =
            "ANNOUNCEMENT";
}
