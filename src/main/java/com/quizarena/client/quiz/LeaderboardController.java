package com.quizarena.client.quiz;

import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.List;
import java.util.Map;

public final class LeaderboardController {
    @FXML private Label heading;
    @FXML private TableView<Row> leaderboardTable;
    @FXML private TableColumn<Row, Number> rank;
    @FXML private TableColumn<Row, String> player;
    @FXML private TableColumn<Row, Number> score;

    public record Row(int rank, String playerName, int score) {}

    @FXML private void initialize() {
        rank.setCellValueFactory(cell -> new ReadOnlyIntegerWrapper(cell.getValue().rank()));
        player.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().playerName()));
        score.setCellValueFactory(cell -> new ReadOnlyIntegerWrapper(cell.getValue().score()));
        leaderboardTable.setPlaceholder(new Label("Scores appear after the first round."));
    }

    void show(Map<String, Object> payload, boolean finished) {
        var entries = (List<?>) payload.get("entries");
        var rows = entries.stream().map(value -> {
            var entry = (Map<?, ?>) value;
            return new Row(((Number) entry.get("rank")).intValue(), (String) entry.get("playerName"),
                    ((Number) entry.get("score")).intValue());
        }).toList();
        leaderboardTable.getItems().setAll(rows);
        heading.setText(finished ? "Final results — saved" : "Leaderboard");
    }
}
