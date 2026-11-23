package com.quizarena.client;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.client.player.PlayerSessionController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class ClientApp extends Application {
    private PlayerSessionController session;

    @Override
    public void start(Stage stage) throws IOException {
        var loader = new FXMLLoader(getClass().getResource("/fxml/player/PlayerShell.fxml"));
        Parent root = loader.load();
        session = loader.getController();
        session.bind(new TcpQuizClient());
        stage.setTitle("QuizArena");
        stage.setMinWidth(720);
        stage.setMinHeight(540);
        stage.setScene(new Scene(root, 1000, 690));
        stage.show();
    }

    @Override
    public void stop() {
        if (session != null) {
            session.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
