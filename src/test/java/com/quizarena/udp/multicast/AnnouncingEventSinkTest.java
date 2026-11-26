package com.quizarena.udp.multicast;

import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnouncingEventSinkTest {

    private final List<String> order = new ArrayList<>();
    private final List<ProtocolMessage> delivered = new ArrayList<>();
    private final List<String> announced = new ArrayList<>();
    private final AnnouncingEventSink sink = new AnnouncingEventSink(
            event -> { order.add("tcp"); delivered.add(event); },
            (room, text) -> { order.add("udp"); announced.add(room + "|" + text); });

    @Test
    void forwardsEveryEventToTcpFirstThenAnnouncesGamePhases() {
        for (String type : List.of(POLL_STARTED, QUESTION_STARTED, BUZZER_OPENED, QUIZ_FINISHED, ROOM_CLOSED)) {
            ProtocolMessage event = event(type);
            sink.broadcast(event);
            assertSame(event, delivered.get(delivered.size() - 1));
        }
        assertEquals(List.of("tcp", "udp", "tcp", "udp", "tcp", "udp", "tcp", "udp", "tcp", "udp"), order);
        assertEquals(5, announced.size());
        assertEquals("ABC123|A new question has started.", announced.get(1));
    }

    @Test
    void neverAnnouncesAnswersScoresOrPayloadText() {
        for (String type : List.of(QUESTION_ENDED, LEADERBOARD_UPDATED, POLL_RESULTS, BUZZER_WINNER, LOBBY_UPDATED)) {
            sink.broadcast(event(type));
        }
        assertEquals(5, delivered.size(), "TCP still receives everything");
        assertEquals(List.of(), announced);

        sink.broadcast(new ProtocolMessage(QUESTION_STARTED, null, "ABC123", null, null,
                Map.of("text", "Capital of France?", "correctOptionId", "B")));
        String text = announced.get(0);
        assertFalse(text.contains("France") || text.contains("correct"), text);
    }

    @Test
    void anAnnouncementFailureDoesNotAffectTcpDelivery() {
        AnnouncingEventSink failing = new AnnouncingEventSink(delivered::add, (room, text) -> {
            throw new IllegalStateException("multicast down");
        });
        failing.broadcast(event(QUIZ_FINISHED));
        assertEquals(1, delivered.size());
    }

    @Test
    void eventsWithoutARoomAreDeliveredButNotAnnounced() {
        sink.broadcast(new ProtocolMessage(POLL_STARTED, null, null, null, null, Map.of()));
        assertEquals(1, delivered.size());
        assertEquals(List.of(), announced);
    }

    @Test
    void requiresBothTargets() {
        assertThrows(NullPointerException.class, () -> new AnnouncingEventSink(null, (r, t) -> { }));
        assertThrows(NullPointerException.class, () -> new AnnouncingEventSink(e -> { }, null));
    }

    private static ProtocolMessage event(String type) {
        return new ProtocolMessage(type, null, "ABC123", null, null, Map.of());
    }
}
