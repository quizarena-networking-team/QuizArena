package com.quizarena.server;

/**
 * Callbacks a {@link ClientSession} uses to reach the reactor that owns it.
 * Both methods must be thread-safe and must never block: producers call them
 * from worker threads while holding game-service locks.
 */
interface SessionHost {

    /** Ask the selector thread to drain this session's outbound queue to the socket. */
    void scheduleFlush(ClientSession session);

    /** Ask the selector thread to close this session. Never closes synchronously. */
    void requestClose(ClientSession session, String reason);
}
