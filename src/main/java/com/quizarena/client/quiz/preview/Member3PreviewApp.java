package com.quizarena.client.quiz.preview;

import com.quizarena.client.quiz.QuizScreenController;
import com.quizarena.quiz.QuizState;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Standalone M3 developer preview. Does not change the shared client launcher or host controls. */
public final class Member3PreviewApp extends Application {
    private Member3PreviewSession session;
    private final List<QuizScreenController> screens = new ArrayList<>();
    private Timeline refresh;

    @Override public void start(Stage stage) throws Exception { showSession(stage); }

    private void showSession(Stage stage) throws Exception {
        disposeSession();
        session = new Member3PreviewSession();
        var current = session;
        var tabs = new TabPane();
        for (var client : current.clients()) {
            var loader = new FXMLLoader(getClass().getResource("/fxml/quiz/QuizScreen.fxml"));
            Parent view = loader.load();
            QuizScreenController controller = loader.getController();
            controller.bind(client, Member3PreviewSession.ROOM, client.playerId());
            screens.add(controller);
            var tab = new Tab(client.playerName(), view);
            tab.setClosable(false);
            tabs.getTabs().add(tab);
        }

        var banner = new Label("MEMBER 3 LOCAL PREVIEW | Sample questions | Results stored in memory only | No TCP, TLS or MySQL");
        banner.setWrapText(true);
        banner.setStyle("-fx-font-weight: bold; -fx-text-fill: #92400e;");
        var help = new Label("Start quiz, answer in any player tab, then wait for the 15-second deadline. "
                + "Use Next question during review. Open the buzzer once and press BUZZ in a tab or run the five-player race.");
        help.setWrapText(true);
        var status = new Label("Ready. Five sample players are already joined.");
        status.setWrapText(true);
        var state = new Label();
        var start = new Button("Start quiz");
        var next = new Button("Next question");
        var buzzer = new Button("Open buzzer");
        var race = new Button("Race 5 players");
        var finish = new Button("Finish quiz");
        var reset = new Button("Reset preview");

        start.setOnAction(event -> run(current, status, current::start, "Quiz started."));
        next.setOnAction(event -> run(current, status, current.service()::nextQuestion, "Next question opened."));
        buzzer.setOnAction(event -> run(current, status,
                () -> current.service().openBuzzer(java.time.Duration.ofSeconds(15), java.time.Duration.ofSeconds(15)),
                "Buzzer opened. Select a player tab or run the race."));
        race.setOnAction(event -> {
            status.setText("Submitting five concurrent buzzer requests...");
            current.raceBuzzers().whenComplete((wins, failure) -> Platform.runLater(() -> {
                if (current != session) return;
                status.setText(failure == null ? "Race completed: " + wins + " winner. Find the winning tab and answer the question."
                        : "Race failed: " + failureMessage(failure));
            }));
        });
        finish.setOnAction(event -> run(current, status, current.service()::finishQuiz,
                "Quiz finished. Results saved in preview memory only; reset/exit clears them."));
        reset.setOnAction(event -> {
            try { showSession(stage); }
            catch (Exception failure) { status.setText("Could not reset preview: " + failureMessage(failure)); }
        });

        refresh = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            QuizState value = current.service().state();
            boolean review = value == QuizState.QUESTION_REVIEW;
            state.setText("State: " + value + " | Standard questions started: " + current.questionsStarted() + "/3");
            start.setDisable(value != QuizState.LOBBY);
            next.setDisable(!review || current.questionsStarted() >= 3);
            buzzer.setDisable(!review || current.buzzerUsed());
            race.setDisable(value != QuizState.BUZZER_OPEN);
            finish.setDisable(!review || current.questionsStarted() < 3);
        }));
        refresh.setCycleCount(Timeline.INDEFINITE);
        var controls = new FlowPane(10, 10, start, next, buzzer, race, finish, reset);
        var header = new VBox(10, banner, help, controls, state, status);
        header.setPadding(new Insets(16));
        var root = new BorderPane(tabs);
        root.setTop(header);
        stage.setScene(new Scene(root, 1100, 900));
        stage.setTitle("QuizArena - Member 3 local preview");
        refresh.playFromStart();
        // Apply state gates immediately, before the first timer pulse.
        next.setDisable(true); buzzer.setDisable(true); race.setDisable(true); finish.setDisable(true);
        if (getParameters().getRaw().contains("--smoke-test")) {
            root.applyCss(); root.layout();
            System.out.println("Member 3 preview loaded: five player views and host controls.");
            disposeSession();
            Platform.exit();
        } else stage.show();
    }

    private void run(Member3PreviewSession owner, Label status, Supplier<? extends CompletableFuture<?>> action, String success) {
        status.setText("Processing...");
        action.get().whenComplete((result, failure) -> Platform.runLater(() -> {
            if (owner == session) status.setText(failure == null ? success : failureMessage(failure));
        }));
    }

    private String failureMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private void disposeSession() {
        if (refresh != null) refresh.stop();
        screens.forEach(QuizScreenController::close);
        screens.clear();
        if (session != null) session.close();
        session = null;
    }

    @Override public void stop() { disposeSession(); }
}
