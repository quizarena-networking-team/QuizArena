package com.quizarena.client;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class ClientApp extends Application {

    @Override
    public void start(Stage stage) {

        Label title = new Label("QuizArena");
        title.setStyle(
                "-fx-font-size: 36px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-text-fill: white;"
        );

        Label subtitle = new Label(
                "Real-Time Multiplayer Quiz Platform"
        );
        subtitle.setStyle("-fx-text-fill: #CBD5E1;");

        Label status = new Label("JavaFX is working!");
        status.setStyle("-fx-text-fill: #34D399;");

        Button button = new Button("Get Started");

        button.setStyle(
                "-fx-background-color: #7C3AED;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 16px;" +
                        "-fx-padding: 12 24;"
        );

        button.setOnAction(event -> {
            status.setText("Welcome to QuizArena!");
        });

        VBox root = new VBox(
                20,
                title,
                subtitle,
                button,
                status
        );

        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: #101827;");

        Scene scene = new Scene(root, 900, 600);

        stage.setTitle("QuizArena");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
