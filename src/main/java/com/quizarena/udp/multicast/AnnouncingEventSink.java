package com.quizarena.udp.multicast;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.quiz.GameEventSink;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import static com.quizarena.protocol.MessageTypes.BUZZER_OPENED;
import static com.quizarena.protocol.MessageTypes.POLL_STARTED;
import static com.quizarena.protocol.MessageTypes.QUESTION_STARTED;
import static com.quizarena.protocol.MessageTypes.QUIZ_FINISHED;
import static com.quizarena.protocol.MessageTypes.ROOM_CLOSED;

/**
 * Wraps a room's {@link GameEventSink}: every event goes to the real (TCP) sink first, then
 * selected game phases are also announced over multicast as short, fixed text.
 *
 * The announcement text never includes event payload, so questions, options, correct answers
 * and scores are never sent over UDP.
 */
public final class AnnouncingEventSink implements GameEventSink {

    private static final System.Logger LOG = System.getLogger(AnnouncingEventSink.class.getName());

    private static final Map<String, String> ANNOUNCEMENTS = Map.of(
            POLL_STARTED, "A live poll is open. Vote now!",
            QUESTION_STARTED, "A new question has started.",
            BUZZER_OPENED, "Buzzer round is open!",
            QUIZ_FINISHED, "The quiz has finished. Check the final leaderboard.",
            ROOM_CLOSED, "The host closed the room.");

    private final GameEventSink delegate;
    private final BiConsumer<String, String> announcer;

    /** @param announcer receives (roomCode, message); normally {@code MulticastAnnouncer::announce} */
    public AnnouncingEventSink(GameEventSink delegate, BiConsumer<String, String> announcer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.announcer = Objects.requireNonNull(announcer, "announcer");
    }

    /** The announcement text for an event type, or null if that type is not announced. */
    public static String announcementFor(String type) {
        return ANNOUNCEMENTS.get(type);
    }

    @Override
    public void broadcast(ProtocolMessage event) {
        delegate.broadcast(event); // authoritative delivery first
        String text = announcementFor(event.type());
        if (text == null || event.roomCode() == null) {
            return;
        }
        try {
            announcer.accept(event.roomCode(), text);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Multicast announcement failed", failure);
        }
    }
}
