package com.quizarena.server.inspector;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.animation.Timeline;
import javafx.scene.control.TextField;
import javafx.util.Duration;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;

import static com.quizarena.metrics.NetworkEvent.Kind.*;

/**
 * Member 4: host-only, read-only view of the real {@link NetworkEventBus}. It shows the bounded
 * ring of recent events and the bus's own running counters, refreshed once per second.
 * Nothing here is simulated. It also lets the host send a best-effort multicast announcement.
 */
public final class NetworkInspectorController {

    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("error");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    @FXML private Label tcpStats;
    @FXML private Label udpStats;
    @FXML private Label alertStats;
    @FXML private TableView<NetworkEvent> eventTable;
    @FXML private TableColumn<NetworkEvent, String> timeColumn;
    @FXML private TableColumn<NetworkEvent, String> kindColumn;
    @FXML private TableColumn<NetworkEvent, String> connectionColumn;
    @FXML private TableColumn<NetworkEvent, String> remoteColumn;
    @FXML private TableColumn<NetworkEvent, Long> bytesColumn;
    @FXML private TableColumn<NetworkEvent, String> detailColumn;
    @FXML private TextField announceRoom;
    @FXML private TextField announceMessage;
    @FXML private Button announceButton;
    @FXML private Label announceStatus;

    private NetworkEventBus bus;
    private BiConsumer<String, String> announcer;
    private Timeline refresher;

    @FXML
    private void initialize() {
        timeColumn.setCellValueFactory(row -> new ReadOnlyStringWrapper(TIME.format(row.getValue().timestamp())));
        kindColumn.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().kind().name()));
        connectionColumn.setCellValueFactory(row -> new ReadOnlyStringWrapper(Objects.toString(row.getValue().connectionId(), "")));
        remoteColumn.setCellValueFactory(row -> new ReadOnlyStringWrapper(Objects.toString(row.getValue().remote(), "")));
        bytesColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().bytes()));
        detailColumn.setCellValueFactory(row -> new ReadOnlyStringWrapper(Objects.toString(row.getValue().detail(), "")));
        eventTable.setPlaceholder(new Label("No network events yet."));
    }

    /**
     * Connects the view to the live bus. {@code announcer} receives (roomCode, message) and may be
     * null when multicast is unavailable. Must be called on the JavaFX thread.
     */
    void bind(NetworkEventBus bus, BiConsumer<String, String> announcer, String defaultRoom) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.announcer = announcer;
        announceRoom.setText(defaultRoom == null ? "" : defaultRoom);
        boolean canAnnounce = announcer != null;
        announceRoom.setDisable(!canAnnounce);
        announceMessage.setDisable(!canAnnounce);
        announceButton.setDisable(!canAnnounce);
        if (!canAnnounce) {
            status("Multicast is unavailable on this server; see the ERROR event.", true);
        }
        refresh();
        refresher = new Timeline(new KeyFrame(Duration.seconds(1), tick -> refresh()));
        refresher.setCycleCount(Animation.INDEFINITE);
        refresher.play();
    }

    /** Reloads the newest-first event list and the counters from the bus. */
    void refresh() {
        List<NetworkEvent> newestFirst = new ArrayList<>(bus.recent());
        Collections.reverse(newestFirst);
        eventTable.getItems().setAll(newestFirst);
        tcpStats.setText(String.format(Locale.ROOT,
                "TCP  accepted %d · disconnected %d · read %s · written %s · rejected frames %d",
                bus.count(ACCEPT), bus.count(DISCONNECT), bytes(bus.totalBytes(READ)),
                bytes(bus.totalBytes(WRITE)), bus.count(FRAME_REJECTED)));
        udpStats.setText(String.format(Locale.ROOT,
                "UDP  received %d (%s) · sent %d (%s) · rejected %d",
                bus.count(UDP_RECEIVED), bytes(bus.totalBytes(UDP_RECEIVED)),
                bus.count(UDP_SENT), bytes(bus.totalBytes(UDP_SENT)), bus.count(UDP_REJECTED)));
        alertStats.setText(String.format(Locale.ROOT, "Presence warnings %d · errors %d · showing last %d events",
                bus.count(PRESENCE_WARNING), bus.count(ERROR), newestFirst.size()));
    }

    @FXML
    private void announce() {
        if (announcer == null) {
            return;
        }
        String room = announceRoom.getText().strip().toUpperCase(Locale.ROOT);
        String message = announceMessage.getText().strip();
        if (room.isEmpty() || message.isEmpty()) {
            status("Enter a room code and a message.", true);
            return;
        }
        announcer.accept(room, message);
        announceMessage.clear();
        status("Announcement queued for room " + room + ". Multicast delivery is best effort.", false);
    }

    /** Stops the refresh timer when the window closes. */
    void stop() {
        if (refresher != null) {
            refresher.stop();
        }
    }

    private void status(String text, boolean error) {
        announceStatus.setText(text);
        announceStatus.pseudoClassStateChanged(INVALID, error);
    }

    static String bytes(long count) {
        if (count < 1024) {
            return count + " B";
        }
        if (count < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", count / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", count / (1024.0 * 1024));
    }
}
