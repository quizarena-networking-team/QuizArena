package com.quizarena.client.player;

import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class PollController {
    enum VoteState { READY, SENDING, LOCKED }

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass CLOSED = PseudoClass.getPseudoClass("closed");
    private static final PseudoClass CHOSEN = PseudoClass.getPseudoClass("chosen");

    @FXML private Label pollQuestion;
    @FXML private Label pollPhase;
    @FXML private VBox pollOptions;
    @FXML private Label pollTotal;
    @FXML private Button submitVoteButton;
    @FXML private Label voteMessage;

    private final ToggleGroup selections = new ToggleGroup();
    private final Map<String, OptionRow> rows = new LinkedHashMap<>();
    private String pollId;
    private boolean pollClosed;
    private boolean roomActions;
    private VoteState voteState = VoteState.READY;
    private Consumer<String> submit;
    private Runnable back;

    void bind(Consumer<String> submit, Runnable back) {
        this.submit = submit;
        this.back = back;
        selections.selectedToggleProperty().addListener((observable, previous, selected) -> updateControls());
        clear();
    }

    void clear() {
        pollId = null;
        pollClosed = false;
        pollQuestion.setText("");
        pollTotal.setText("");
        rows.clear();
        selections.getToggles().clear();
        pollOptions.getChildren().clear();
        setVoteState(VoteState.READY, "", false);
    }

    String pollId() { return pollId; }
    boolean isClosed() { return pollClosed; }

    boolean start(Map<String, Object> payload) {
        if (!(payload.get("pollId") instanceof String id) || id.isBlank() || id.equals(pollId)
                || !(payload.get("question") instanceof String question) || question.isBlank()
                || !(payload.get("options") instanceof Map<?, ?> suppliedOptions) || suppliedOptions.size() < 2) {
            return false;
        }
        var options = new TreeMap<String, String>();
        for (var entry : suppliedOptions.entrySet()) {
            if (!(entry.getKey() instanceof String optionId) || optionId.isBlank()
                    || !(entry.getValue() instanceof String label) || label.isBlank()) {
                return false;
            }
            options.put(optionId, label);
        }
        Map<String, Integer> counts = readCounts(payload, options.keySet());
        if (counts == null) {
            return false;
        }

        clear();
        pollId = id;
        pollQuestion.setText(question);
        pollPhase.setText("Voting open");
        pollPhase.pseudoClassStateChanged(CLOSED, false);
        options.forEach(this::addOption);
        renderCounts(counts);
        setVoteState(VoteState.READY, "Choose an option to vote.", false);
        return true;
    }

    boolean update(Map<String, Object> payload, boolean closing) {
        if (pollId == null || !pollId.equals(payload.get("pollId")) || (pollClosed && !closing)) {
            return false;
        }
        Map<String, Integer> counts = readCounts(payload, rows.keySet());
        if (counts != null) {
            renderCounts(counts);
        }
        if (closing) {
            closeVoting();
        }
        return counts != null || closing;
    }

    void closeVoting() {
        pollClosed = true;
        pollPhase.setText("Poll closed");
        pollPhase.pseudoClassStateChanged(CLOSED, true);
        if (voteState == VoteState.READY) {
            setVoteState(VoteState.LOCKED, "Voting has ended.", false);
        }
        updateControls();
    }

    void setRoomActions(boolean enabled) {
        roomActions = enabled;
        updateControls();
    }

    void setVoteState(VoteState state, String message, boolean error) {
        voteState = state;
        voteMessage.setText(message);
        voteMessage.pseudoClassStateChanged(ERROR, error);
        updateControls();
    }

    private void updateControls() {
        boolean canChoose = roomActions && pollId != null && !pollClosed && voteState == VoteState.READY;
        rows.values().forEach(row -> row.choice().setDisable(!canChoose));
        submitVoteButton.setDisable(!canChoose || selections.getSelectedToggle() == null);
        submitVoteButton.setText(voteState == VoteState.SENDING ? "Submitting…" : "Submit vote");
    }

    private void addOption(String id, String text) {
        var option = new RadioButton(text);
        option.setMaxWidth(Double.MAX_VALUE);
        option.setWrapText(true);
        option.setToggleGroup(selections);
        option.setUserData(id);
        HBox.setHgrow(option, Priority.ALWAYS);

        var count = new Label();
        count.getStyleClass().add("option-count");
        count.setMinWidth(Label.USE_PREF_SIZE);
        var title = new HBox(12, option, count);
        title.setAlignment(Pos.CENTER_LEFT);

        var meter = new ProgressBar(0);
        meter.setMaxWidth(Double.MAX_VALUE);
        meter.getStyleClass().add("result-meter");
        meter.setAccessibleText("Share of votes for " + text);
        var row = new VBox(9, title, meter);
        row.getStyleClass().add("poll-option");
        option.selectedProperty().addListener((observable, previous, selected) -> row.pseudoClassStateChanged(CHOSEN, selected));
        pollOptions.getChildren().add(row);
        rows.put(id, new OptionRow(option, count, meter));
    }

    private void renderCounts(Map<String, Integer> counts) {
        long total = counts.values().stream().mapToLong(Integer::longValue).sum();
        pollTotal.setText(total + (total == 1 ? " vote counted" : " votes counted"));
        rows.forEach((id, row) -> {
            int count = counts.get(id);
            row.count().setText(count + (count == 1 ? " vote" : " votes"));
            row.meter().setProgress(total == 0 ? 0 : (double) count / total);
        });
    }

    private static Map<String, Integer> readCounts(Map<String, Object> payload, Set<String> optionIds) {
        if (!(payload.get("counts") instanceof Map<?, ?> values) || !optionIds.equals(values.keySet())) {
            return null;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String id : optionIds) {
            if (!(values.get(id) instanceof Integer count) || count < 0) {
                return null;
            }
            counts.put(id, count);
        }
        return counts;
    }

    @FXML
    private void submit() {
        if (!submitVoteButton.isDisabled() && selections.getSelectedToggle() != null) {
            submit.accept((String) selections.getSelectedToggle().getUserData());
        }
    }

    @FXML private void back() { back.run(); }

    private record OptionRow(RadioButton choice, Label count, ProgressBar meter) {}
}
