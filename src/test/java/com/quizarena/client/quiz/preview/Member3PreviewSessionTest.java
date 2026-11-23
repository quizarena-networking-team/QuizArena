package com.quizarena.client.quiz.preview;

import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class Member3PreviewSessionTest {
    @Test void previewRunsThreeRealTimedQuestionsBuzzerAndMatchingFinalResults() throws Exception {
        try (var session = new Member3PreviewSession(Duration.ofSeconds(2))) {
            var ended = new LinkedBlockingQueue<ProtocolMessage>();
            var winners = new LinkedBlockingQueue<ProtocolMessage>();
            var finished = new LinkedBlockingQueue<ProtocolMessage>();
            try (var subscription = session.clients().getFirst().subscribe(event -> {
                if (QUESTION_ENDED.equals(event.type())) ended.add(event);
                if (BUZZER_WINNER.equals(event.type())) winners.add(event);
                if (QUIZ_FINISHED.equals(event.type())) finished.add(event);
            })) {
                await(session.start());
                var player = session.clients().getFirst();
                assertEquals("OK", await(answer(player, session.activeQuestion(), "A")).status());
                assertEquals("ANSWER_ALREADY_SUBMITTED", await(answer(player, session.activeQuestion(), "B")).errorCode());
                assertNotNull(ended.poll(5, TimeUnit.SECONDS), "Real scheduler should end the first question");

                await(session.service().openBuzzer(Duration.ofSeconds(5), Duration.ofSeconds(5)));
                assertEquals(1, await(session.raceBuzzers()));
                var winner = winners.poll(5, TimeUnit.SECONDS);
                assertNotNull(winner);
                var winningClient = session.clients().stream()
                        .filter(client -> client.playerId().equals(winner.payload().get("playerId"))).findFirst().orElseThrow();
                assertEquals("OK", await(answer(winningClient, session.activeQuestion(), "D")).status());
                assertNotNull(ended.poll(5, TimeUnit.SECONDS));

                await(session.service().nextQuestion());
                assertEquals("OK", await(answer(player, session.activeQuestion(), "B")).status());
                assertNotNull(ended.poll(5, TimeUnit.SECONDS));
                await(session.service().nextQuestion());
                assertEquals("OK", await(answer(player, session.activeQuestion(), "C")).status());
                assertNotNull(ended.poll(5, TimeUnit.SECONDS));
                var snapshot = await(session.service().finishQuiz());
                assertSame(snapshot, session.saved());
                assertEquals(350, snapshot.entries().stream().mapToInt(entry -> entry.score()).sum());
                assertEquals(3, session.questionsStarted());
                assertTrue(session.buzzerUsed());
                assertNotNull(finished.poll(5, TimeUnit.SECONDS));
                Object previous = null;
                for (var client : session.clients()) {
                    var reply = await(client.request(new ProtocolMessage(GET_LEADERBOARD, UUID.randomUUID().toString(),
                            Member3PreviewSession.ROOM, null, null, Map.of())));
                    assertEquals("OK", reply.status());
                    var entries = (List<?>) reply.payload().get("entries");
                    assertEquals(5, entries.size());
                    if (previous != null) assertEquals(previous, entries);
                    previous = entries;
                }
            }
        }
    }

    private CompletableFuture<ProtocolMessage> answer(Member3PreviewSession.LocalClient client,
                                                       ProtocolMessage question, String option) {
        return client.request(new ProtocolMessage(SUBMIT_ANSWER, UUID.randomUUID().toString(),
                Member3PreviewSession.ROOM, null, null,
                Map.of("questionId", question.payload().get("questionId"),
                        "roundId", question.payload().get("roundId"), "optionId", option)));
    }

    private <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
}
