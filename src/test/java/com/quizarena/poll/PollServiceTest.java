package com.quizarena.poll;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.quizarena.poll.PollTestSupport.*;
import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PollServiceTest {
    private static final Map<String, Integer> EMPTY_COUNTS = Map.of("A", 0, "B", 0, "C", 0);

    @Test
    void startsPollWithZeroCountsAndImmutablePublicDetails() {
        try (var fixture = new Fixture()) {
            assertTrue(fixture.service.currentPoll().isEmpty());
            var options = new HashMap<>(OPTIONS);
            PollSnapshot opened = fixture.service.startPoll(QUESTION, options);
            options.put("A", "Changed by caller");

            assertFalse(opened.pollId().isBlank());
            assertEquals(PollSnapshot.State.OPEN, opened.state());
            assertEquals(QUESTION, opened.question());
            assertEquals(OPTIONS, opened.options());
            assertEquals(EMPTY_COUNTS, opened.counts());
            assertEquals(opened, fixture.service.currentPoll().orElseThrow());
            assertThrows(UnsupportedOperationException.class, () -> opened.options().put("X", "Other"));
            assertThrows(UnsupportedOperationException.class, () -> opened.counts().put("B", 100));

            assertEquals(List.of(new ProtocolMessage(POLL_STARTED, null, ROOM, null, null,
                    Map.of("pollId", opened.pollId(), "question", QUESTION, "options", OPTIONS, "counts", EMPTY_COUNTS))),
                    fixture.events);
        }
    }

    @Test
    void rejectsInvalidDefinitionsAndFramesBeforePublishingState() {
        try (var fixture = new Fixture()) {
            rejected("BAD_REQUEST", () -> fixture.service.startPoll(" ", OPTIONS));
            rejected("BAD_REQUEST", () -> fixture.service.startPoll(QUESTION, Map.of("A", "Only option")));
            rejected("BAD_REQUEST", () -> fixture.service.startPoll(QUESTION, Map.of("A", "TCP", "B", " ")));
            rejected("BAD_REQUEST", () -> fixture.service.startPoll(QUESTION, Map.of(" ", "TCP", "B", "UDP")));
            rejected("BAD_REQUEST", () -> fixture.service.startPoll("é".repeat(MessageCodec.MAX_FRAME_BYTES / 2), OPTIONS));
            assertTrue(fixture.service.currentPoll().isEmpty());
            assertTrue(fixture.events.isEmpty());
        }
    }

    @Test
    void onlyOneConcurrentStartCanOpenAPoll() throws Exception {
        try (var fixture = new Fixture()) {
            List<String> outcomes = race(2, index -> {
                try {
                    return fixture.service.startPoll("Question " + index, OPTIONS).pollId();
                } catch (PollException failure) {
                    return failure.code();
                }
            });
            assertEquals(1, outcomes.stream().filter("BAD_REQUEST"::equals).count());
            assertTrue(outcomes.contains(fixture.service.currentPoll().orElseThrow().pollId()));
            assertEquals(List.of(POLL_STARTED), fixture.events.stream().map(ProtocolMessage::type).toList());
        }
    }

    @Test
    void closesOnceKeepsFinalCountsAndStartsANewBallot() {
        try (var fixture = new Fixture()) {
            PollSnapshot first = fixture.start();
            fixture.service.submitVote("p1", first.pollId(), "B");
            var closed = fixture.service.closePoll(first.pollId());

            assertEquals(PollSnapshot.State.CLOSED, closed.state());
            assertEquals(Map.of("A", 0, "B", 1, "C", 0), closed.counts());
            assertEquals(EMPTY_COUNTS, first.counts(), "The initial snapshot must remain unchanged");
            assertEquals(closed, fixture.service.currentPoll().orElseThrow());
            assertEquals(closed, fixture.service.closePoll(first.pollId()));
            assertEquals(List.of(POLL_STARTED, POLL_RESULTS, POLL_CLOSED),
                    fixture.events.stream().map(ProtocolMessage::type).toList());
            assertEquals(new ProtocolMessage(POLL_CLOSED, null, ROOM, null, null,
                    Map.of("pollId", first.pollId(), "counts", closed.counts())), fixture.events.getLast());
            rejected(POLL_CLOSED, () -> fixture.service.submitVote("p2", first.pollId(), "A"));

            PollSnapshot second = fixture.start();
            assertNotEquals(first.pollId(), second.pollId());
            assertEquals(EMPTY_COUNTS, second.counts());
            rejected("BAD_REQUEST", () -> fixture.service.submitVote("p2", first.pollId(), "A"));
            rejected("BAD_REQUEST", () -> fixture.service.closePoll(first.pollId()));
            fixture.service.submitVote("p1", second.pollId(), "A");
            assertEquals(Map.of("A", 1, "B", 0, "C", 0), fixture.service.currentPoll().orElseThrow().counts());
        }
    }

    @Test
    void voteRacingCloseEitherContributesToFinalCountsOrIsRejected() throws Exception {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            List<String> outcomes = race(2, index -> {
                if (index == 0) {
                    try {
                        fixture.service.submitVote("p1", pollId, "B");
                        return "accepted";
                    } catch (PollException failure) {
                        return failure.code();
                    }
                }
                fixture.service.closePoll(pollId);
                return "closed";
            });

            boolean accepted = "accepted".equals(outcomes.getFirst());
            assertEquals(accepted ? "accepted" : POLL_CLOSED, outcomes.getFirst());
            assertEquals(accepted ? 1 : 0, fixture.service.currentPoll().orElseThrow().counts().get("B"));
            assertEquals(accepted ? List.of(POLL_STARTED, POLL_RESULTS, POLL_CLOSED) : List.of(POLL_STARTED, POLL_CLOSED),
                    fixture.events.stream().map(ProtocolMessage::type).toList());
        }
    }

    @Test
    void roomShutdownClearsStateAndRejectsFurtherActions() {
        try (var fixture = new Fixture()) {
            String pollId = fixture.start().pollId();
            fixture.service.submitVote("p1", pollId, "A");
            fixture.service.close();
            fixture.service.close();
            assertTrue(fixture.service.currentPoll().isEmpty());
            rejected("ROOM_CLOSED", () -> fixture.service.submitVote("p2", pollId, "B"));
            rejected("ROOM_CLOSED", () -> fixture.service.closePoll(pollId));
            rejected("ROOM_CLOSED", fixture::start);
            assertEquals(List.of(POLL_STARTED, POLL_RESULTS), fixture.events.stream().map(ProtocolMessage::type).toList());
        }
    }

    @Test
    void failedEventEnqueueDoesNotUndoAnAcceptedVote() {
        var directory = new Directory();
        directory.join("p1");
        List<ProtocolMessage> events = new ArrayList<>();
        try (var service = new PollService(ROOM, directory, event -> {
            if (POLL_RESULTS.equals(event.type())) {
                throw new IllegalStateException("Test outbound queue is full");
            }
            events.add(event);
        })) {
            String pollId = service.startPoll(QUESTION, OPTIONS).pollId();
            service.submitVote("p1", pollId, "B");
            rejected("ALREADY_VOTED", () -> service.submitVote("p1", pollId, "A"));
            var closed = service.closePoll(pollId);
            assertEquals(Map.of("A", 0, "B", 1, "C", 0), closed.counts());
            assertEquals(List.of(POLL_STARTED, POLL_CLOSED), events.stream().map(ProtocolMessage::type).toList());
            assertEquals(closed.counts(), events.getLast().payload().get("counts"));
        }
    }
}
