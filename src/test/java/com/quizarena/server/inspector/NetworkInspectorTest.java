package com.quizarena.server.inspector;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt in on a desktop: mvn -Dquizarena.uiTests=true test. No windows are opened. */
@EnabledIfSystemProperty(named = "quizarena.uiTests", matches = "true")
class NetworkInspectorTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        var ready = new CountDownLatch(1);
        try {
            Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test
    void showsRealBusEventsNewestFirstWithCounters() throws Exception {
        NetworkEventBus bus = new NetworkEventBus();
        bus.publish(NetworkEvent.Kind.ACCEPT, "c1", "/127.0.0.1:5555", 0, "client connected");
        bus.publish(NetworkEvent.Kind.READ, "c1", "/127.0.0.1:5555", 87, null);
        bus.publish(NetworkEvent.Kind.UDP_RECEIVED, null, "192.168.1.20:6000", 31, "DISCOVER");
        bus.publish(NetworkEvent.Kind.PRESENCE_WARNING, "p-1", "192.168.1.20:6001", 0, "no heartbeat");

        onFx(() -> {
            Loaded view = load(bus, null);
            @SuppressWarnings("unchecked")
            TableView<NetworkEvent> table = (TableView<NetworkEvent>) view.root.lookup("#eventTable");
            assertEquals(4, table.getItems().size());
            assertEquals(NetworkEvent.Kind.PRESENCE_WARNING, table.getItems().get(0).kind());
            assertTrue(text(view, "#tcpStats").contains("accepted 1"));
            assertTrue(text(view, "#tcpStats").contains("read 87 B"));
            assertTrue(text(view, "#udpStats").contains("received 1 (31 B)"));
            assertTrue(text(view, "#alertStats").contains("Presence warnings 1"));
            assertTrue(((Button) view.root.lookup("#announceButton")).isDisabled(), "no multicast, no button");

            bus.publish(NetworkEvent.Kind.UDP_SENT, null, "239.10.10.10:5003", 90, "ANNOUNCEMENT");
            view.controller.refresh();
            assertEquals(5, table.getItems().size());
            view.controller.stop();
        });
    }

    @Test
    void sendsAnnouncementsWithANormalizedRoomCode() throws Exception {
        List<String> sent = new ArrayList<>();
        onFx(() -> {
            Loaded view = load(new NetworkEventBus(), (room, message) -> sent.add(room + "|" + message));
            TextField room = (TextField) view.root.lookup("#announceRoom");
            TextField message = (TextField) view.root.lookup("#announceMessage");
            Button send = (Button) view.root.lookup("#announceButton");
            assertEquals("DEMO01", room.getText());

            message.setText("  ");
            send.fire();
            assertEquals(List.of(), sent);
            assertTrue(text(view, "#announceStatus").contains("Enter"));

            room.setText(" abc123 ");
            message.setText("Round starts in 1 minute");
            send.fire();
            assertEquals(List.of("ABC123|Round starts in 1 minute"), sent);
            assertEquals("", message.getText());
            view.controller.stop();
        });
    }

    @Test
    void formatsByteCounts() {
        assertEquals("512 B", NetworkInspectorController.bytes(512));
        assertEquals("2.0 KB", NetworkInspectorController.bytes(2048));
        assertEquals("3.0 MB", NetworkInspectorController.bytes(3L * 1024 * 1024));
    }

    private record Loaded(Parent root, NetworkInspectorController controller) {
    }

    private static Loaded load(NetworkEventBus bus, java.util.function.BiConsumer<String, String> announcer) {
        try {
            var loader = new FXMLLoader(NetworkInspectorTest.class.getResource("/fxml/inspector/NetworkInspector.fxml"));
            Parent root = loader.load();
            new Scene(root);
            root.applyCss();
            NetworkInspectorController controller = loader.getController();
            controller.bind(bus, announcer, "DEMO01");
            return new Loaded(root, controller);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String text(Loaded view, String id) {
        return ((Label) view.root.lookup(id)).getText();
    }

    private static void onFx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }
}
