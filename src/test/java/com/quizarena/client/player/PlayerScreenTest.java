package com.quizarena.client.player;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/** Isolates the player views from other tests that initialize the JavaFX toolkit in the Maven JVM. */
@EnabledIfSystemProperty(named = "quizarena.uiTests", matches = "true")
@Timeout(35)
public class PlayerScreenTest {
    @Test void loadsFxmlAndShowsValidatedConnectionTransitions() throws Exception { runScenario("connect"); }
    @Test void realRefusedConnectionAllowsRetry() throws Exception { runScenario("refused"); }
    @Test void joinReplyAndRoomEventsControlLobbyAndIdentity() throws Exception { runScenario("join"); }
    @Test void rendersPollVotesLiveTotalsAndClosure() throws Exception { runScenario("poll"); }
    @Test void rejectsLateRepliesAndUnsubscribesAfterLeaving() throws Exception { runScenario("stale"); }
    @Test void lossClearsMembershipAndAllowsSafeReconnect() throws Exception { runScenario("loss"); }
    @Test void playerFlowUsesRealTcpRequestsAndEvents() throws Exception { runScenario("loopback"); }
    @Test void quizAndPollingShareOneGameplaySocket() throws Exception { runScenario("quiz-loopback"); }
    @Test void quizSurvivesEarlyEventsAndRebindsAfterConnectionLoss() throws Exception { runScenario("quiz-session"); }
    @Test void connectionLossWinsOverPendingRepliesAfterPollAndQuizNavigation() throws Exception { runScenario("reply-loss-race"); }
    @Test void compactQuizShowsTheBuzzerAndAllFinalResultsColumns() throws Exception { runScenario("quiz-compact"); }

    private static void runScenario(String scenario) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) {
            java = java.resolveSibling("java.exe");
        }
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = Files.createTempFile(Path.of("target"), "player-ui-", ".log");
        try {
            Process child = new ProcessBuilder(java.toString(), "-cp", classPath, PlayerScreenTest.class.getName(), scenario)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(child.waitFor(25, SECONDS), () -> "Player UI scenario timed out\n" + readLog(log));
                assertEquals(0, child.exitValue(), () -> readLog(log));
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly().waitFor(3, SECONDS);
                }
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    public static void main(String[] args) throws Exception {
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.complete(null);
        });
        try {
            ready.get(8, SECONDS);
            switch (args[0]) {
                case "connect" -> PlayerUiScenarios.connect();
                case "refused" -> PlayerUiScenarios.refused();
                case "join" -> PlayerUiScenarios.join();
                case "poll" -> PlayerUiScenarios.poll();
                case "stale" -> PlayerUiScenarios.stale();
                case "loss" -> PlayerUiScenarios.loss();
                case "loopback" -> PlayerUiScenarios.loopback();
                case "quiz-loopback" -> QuizUiScenarios.loopback();
                case "quiz-session" -> QuizUiScenarios.sessionLifecycle();
                case "reply-loss-race" -> QuizUiScenarios.replyLossRace();
                case "quiz-compact" -> QuizUiScenarios.compactQuiz();
                default -> throw new IllegalArgumentException("Unknown player UI scenario");
            }
        } finally {
            Platform.exit();
        }
    }
}
