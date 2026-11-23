package com.quizarena.quiz;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public interface QuestionScheduler extends AutoCloseable {
    interface Ticket { void cancel(); }
    Ticket schedule(Duration delay, Runnable action);
    @Override void close();

    static QuestionScheduler system() {
        var executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "m3-question-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        return new QuestionScheduler() {
            public Ticket schedule(Duration delay, Runnable action) {
                var future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            }
            public void close() { executor.shutdownNow(); }
        };
    }
}
