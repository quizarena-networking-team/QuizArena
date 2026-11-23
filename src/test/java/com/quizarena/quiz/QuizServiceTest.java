package com.quizarena.quiz;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.quizarena.protocol.MessageTypes.*;
import static com.quizarena.quiz.QuizTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class QuizServiceTest {
    @Test void duplicateConcurrentAnswersAwardExactlyOnce() throws Exception {
        try (var h = new Harness()) {
            h.start();
            String round = h.round();
            var accepted = race(25, i -> h.service.submitAnswer("p0", "1", round, "A"));
            assertEquals(1, accepted.stream().filter(Boolean::booleanValue).count());
            h.completeStandardQuestions();
            var result = await(h.service.finishQuiz());
            var player = result.entries().stream().filter(e -> e.playerId().equals("p0")).findFirst().orElseThrow();
            assertEquals(100, player.score());
            assertEquals(1, player.answeredCount());
            assertEquals(1, player.correctCount());
        }
    }

    @Test void independentPlayersAreGradedAndTiesHaveStableCompetitionRanks() throws Exception {
        try (var h = new Harness()) {
            h.start();
            String round = h.round();
            assertTrue(race(5, i -> h.service.submitAnswer("p" + i, "1", round, i < 2 ? "A" : "B"))
                    .stream().allMatch(Boolean::booleanValue));
            h.completeStandardQuestions();
            var entries = await(h.service.finishQuiz()).entries();
            assertEquals(List.of(100, 100, 0, 0, 0), entries.stream().map(ScoreService.Entry::score).toList());
            assertEquals(List.of(1, 1, 3, 3, 3), entries.stream().map(ScoreService.Entry::rank).toList());
        }
    }

    @Test void correctnessAndScoreChangesStayHiddenUntilReview() {
        try (var h = new Harness()) {
            h.start();
            var started = h.last(QUESTION_STARTED).payload();
            assertFalse(started.containsKey("correctOptionId"));
            assertFalse(started.containsKey("correctOption"));
            var reply = await(h.service.submitAnswer("p0", "1", h.round(), "A"));
            assertEquals(Map.of("accepted", true, "questionId", "1", "roundId", h.round()), reply);
            var entries = (List<?>) await(h.service.leaderboard("p0")).get("entries");
            assertTrue(entries.stream().allMatch(e -> ((Map<?, ?>) e).get("score").equals(0)));
            assertEquals(0, h.events.stream().filter(e -> e.type().equals(LEADERBOARD_UPDATED)).count());
            h.expire();
            assertEquals("A", h.last(QUESTION_ENDED).payload().get("correctOptionId"));
            assertEquals(100, ((Map<?, ?>) ((List<?>) await(h.service.leaderboard("p0")).get("entries")).getFirst()).get("score"));
        }
    }

    @Test void exactDeadlineRejectsAnswerEvenIfTimerHasNotRun() {
        try (var h = new Harness()) {
            h.start();
            String round = h.round();
            h.clock.advance(Duration.ofSeconds(20));
            error("QUESTION_CLOSED", h.service.submitAnswer("p0", "1", round, "A"));
            assertEquals(QuizState.QUESTION_REVIEW, h.service.state());
        }
    }

    @Test void wallClockChangesDoNotChangeAcceptanceDeadline() {
        try (var h = new Harness()) {
            h.start();
            h.clock.wall.addAndGet(Duration.ofDays(1).toMillis());
            await(h.service.submitAnswer("p0", "1", h.round(), "A"));
            h.clock.wall.addAndGet(-Duration.ofDays(2).toMillis());
            h.clock.nanos.addAndGet(Duration.ofSeconds(20).toNanos());
            error("QUESTION_CLOSED", h.service.submitAnswer("p1", "1", h.round(), "A"));
        }
    }

    @Test void cancelledTimerCannotCloseTheNextQuestion() {
        try (var h = new Harness()) {
            h.start();
            Runnable oldTimer = h.scheduler.jobs.getFirst().action;
            h.expire();
            await(h.service.nextQuestion());
            oldTimer.run();
            assertEquals(QuizState.QUESTION_OPEN, h.service.state());
            await(h.service.submitAnswer("p0", "2", h.round(), "A"));
        }
    }

    @Test void earlyTimerReschedulesInsteadOfEndingQuestion() {
        try (var h = new Harness()) {
            h.start();
            h.scheduler.jobs.getFirst().action.run();
            assertEquals(QuizState.QUESTION_OPEN, h.service.state());
            h.expire();
            assertEquals(QuizState.QUESTION_REVIEW, h.service.state());
            assertEquals(1, h.events.stream().filter(e -> e.type().equals(QUESTION_ENDED)).count());
        }
    }

    @Test void invalidOptionAndInactiveRoundDoNotConsumeAnAnswer() {
        try (var h = new Harness()) {
            h.start();
            error("BAD_REQUEST", h.service.submitAnswer("p0", "1", h.round(), "Z"));
            error("QUESTION_CLOSED", h.service.submitAnswer("p0", "2", h.round(), "A"));
            error("QUESTION_CLOSED", h.service.submitAnswer("p0", "1", "old-round", "A"));
            await(h.service.submitAnswer("p0", "1", h.round(), "A"));
        }
    }

    @Test void removedPlayersAndLateJoinersCannotAnswer() {
        try (var h = new Harness()) {
            h.start();
            h.directory.roster.remove("p0");
            h.directory.roster.put("new", new PlayerDirectory.Player("new", "Late joiner"));
            error("INVALID_PLAYER", h.service.submitAnswer("p0", "1", h.round(), "A"));
            error("INVALID_PLAYER", h.service.submitAnswer("new", "1", h.round(), "A"));
        }
    }

    @RepeatedTest(10) void fiveSimultaneousBuzzersHaveOneWinner() throws Exception {
        try (var h = new Harness()) {
            h.start();
            h.expire();
            await(h.service.openBuzzer(Duration.ofSeconds(10), Duration.ofSeconds(15)));
            String round = h.round();
            var accepted = race(5, i -> h.service.pressBuzzer("p" + i, "4", round));
            assertEquals(1, accepted.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, h.events.stream().filter(e -> e.type().equals(BUZZER_WINNER)).count());
            String winner = (String) h.last(BUZZER_WINNER).payload().get("playerId");
            String loser = winner.equals("p0") ? "p1" : "p0";
            error("UNAUTHORIZED", h.service.submitAnswer(loser, "4", round, "A"));
            await(h.service.submitAnswer(winner, "4", round, "A"));
            error("QUESTION_CLOSED", h.service.submitAnswer(winner, "4", round, "A"));
            var first = (Map<?, ?>) ((List<?>) await(h.service.leaderboard(winner)).get("entries")).getFirst();
            assertEquals(winner, first.get("playerId"));
            assertEquals(50, first.get("score"));
        }
    }

    @Test void buzzerNoWinnerAndWinnerAnswerTimeoutReturnToReview() {
        try (var h = new Harness()) {
            h.start();
            h.expire();
            await(h.service.openBuzzer(Duration.ofSeconds(5), Duration.ofSeconds(10)));
            h.clock.advance(Duration.ofSeconds(5));
            h.scheduler.runDue();
            assertEquals(QuizState.QUESTION_REVIEW, h.service.state());
            error("BUZZER_CLOSED", h.service.pressBuzzer("p0", "4", h.round()));
            error("BAD_REQUEST", h.service.openBuzzer(Duration.ofSeconds(5), Duration.ofSeconds(10)));
        }
        try (var h = new Harness()) {
            h.start(); h.expire();
            await(h.service.openBuzzer(Duration.ofSeconds(5), Duration.ofSeconds(10)));
            await(h.service.pressBuzzer("p0", "4", h.round()));
            h.clock.advance(Duration.ofSeconds(10));
            error("QUESTION_CLOSED", h.service.submitAnswer("p0", "4", h.round(), "A"));
        }
    }

    @Test void normalQuestionCannotBecomeAnAlreadyRevealedBuzzerQuestion() {
        try (var h = new Harness()) {
            await(h.service.startQuiz("1"));
            h.expire();
            error("BAD_REQUEST", h.service.openBuzzer(Duration.ofSeconds(5), Duration.ofSeconds(10)));
        }
    }

    @Test void hostCannotAdvanceOpenQuestionOrFinishEarly() {
        try (var h = new Harness()) {
            h.start();
            error("BAD_REQUEST", h.service.nextQuestion());
            error("BAD_REQUEST", h.service.finishQuiz());
            h.expire();
            error("BAD_REQUEST", h.service.finishQuiz());
        }
    }

    @Test void persistenceFailureAllowsRetryOfSameFrozenSnapshot() {
        var calls = new AtomicInteger();
        var saved = new AtomicReference<ResultSnapshot>();
        try (var h = new Harness(id -> fixture(), snapshot -> {
            if (calls.getAndIncrement() == 0) { saved.set(snapshot); throw new SQLException("offline"); }
            assertSame(saved.get(), snapshot);
        })) {
            h.start(); h.completeStandardQuestions();
            error("INTERNAL_ERROR", h.service.finishQuiz());
            assertEquals(QuizState.QUESTION_REVIEW, h.service.state());
            assertTrue(h.events.stream().noneMatch(e -> e.type().equals(QUIZ_FINISHED)));
            error("BAD_REQUEST", h.service.openBuzzer(Duration.ofSeconds(5), Duration.ofSeconds(10)));
            var completed = await(h.service.finishQuiz());
            assertSame(completed, await(h.service.finishQuiz()));
            assertEquals(2, calls.get());
            assertEquals(1, h.events.stream().filter(e -> e.type().equals(QUIZ_FINISHED)).count());
        }
    }

    @Test void slowDatabaseDoesNotHoldRoomLockOrCompleteBeforeCommit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var h = new Harness(id -> fixture(), snapshot -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException("test timed out"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException(failure); }
        })) {
            h.start(); h.completeStandardQuestions();
            var finish = h.service.finishQuiz();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(QuizState.SAVING, h.service.state());
                await(h.service.leaderboard("p0"));
                assertFalse(finish.isDone());
                assertTrue(h.events.stream().noneMatch(e -> e.type().equals(QUIZ_FINISHED)));
            } finally { release.countDown(); }
            assertNotNull(await(finish));
        }
    }

    @Test void loadingFailureRestoresLobbyAndCanRetry() {
        var calls = new AtomicInteger();
        try (var h = new Harness(id -> {
            if (calls.getAndIncrement() == 0) throw new SQLException("offline");
            return fixture();
        }, null)) {
            error("INTERNAL_ERROR", h.service.startQuiz("1", "4"));
            assertEquals(QuizState.LOBBY, h.service.state());
            h.start();
            assertEquals(QuizState.QUESTION_OPEN, h.service.state());
        }
    }

    @Test void serviceCloseCancelsTimersAndRejectsNewWork() {
        try (var h = new Harness()) {
            h.start();
            h.service.close();
            h.expire();
            assertEquals(QuizState.CLOSED, h.service.state());
            assertTrue(h.events.stream().noneMatch(e -> e.type().equals(QUESTION_ENDED)));
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> h.service.nextQuestion().join());
            assertInstanceOf(QuizException.class, failure.getCause());
        }
    }
}
