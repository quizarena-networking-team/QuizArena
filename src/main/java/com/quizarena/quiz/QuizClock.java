package com.quizarena.quiz;

/** Monotonic time enforces deadlines; wall time is only a client display hint. */
public interface QuizClock {
    long nanoTime();
    long epochMillis();

    static QuizClock system() {
        return new QuizClock() {
            public long nanoTime() { return System.nanoTime(); }
            public long epochMillis() { return System.currentTimeMillis(); }
        };
    }
}
