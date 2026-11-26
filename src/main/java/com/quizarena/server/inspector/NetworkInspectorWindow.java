package com.quizarena.server.inspector;

import com.quizarena.metrics.NetworkEventBus;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * Opens the network inspector inside the server JVM (run {@code ServerMain --inspector} on the
 * host computer). It reads the live {@link NetworkEventBus} directly, so it needs no extra
 * network protocol and is only visible on the computer running the server.
 */
public final class NetworkInspectorWindow {

    private NetworkInspectorWindow() {
    }

    /**
     * Starts JavaFX (if needed) and shows the inspector. Returns immediately. Closing the window
     * does not stop the server. If no display is available, the problem is reported and the
     * server keeps running.
     */
    public static void open(NetworkEventBus bus, BiConsumer<String, String> announcer, String defaultRoom) {
        Runnable show = () -> {
            try {
                FXMLLoader loader = new FXMLLoader(NetworkInspectorWindow.class.getResource("/fxml/inspector/NetworkInspector.fxml"));
                Parent root = loader.load();
                NetworkInspectorController controller = loader.getController();
                controller.bind(bus, announcer, defaultRoom);
                Stage stage = new Stage();
                stage.setTitle("QuizArena - Network Inspector (host only)");
                stage.setScene(new Scene(root, 1100, 680));
                stage.setMinWidth(760);
                stage.setMinHeight(480);
                stage.setOnHidden(closed -> controller.stop());
                stage.show();
            } catch (IOException | RuntimeException failure) {
                System.err.println("Network inspector could not be opened: " + failure);
            }
        };
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                show.run();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(show);
        } catch (RuntimeException noDisplay) {
            System.err.println("Network inspector unavailable (no graphical display): " + noDisplay);
        }
    }
}
