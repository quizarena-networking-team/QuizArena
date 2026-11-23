package com.quizarena.poll;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static com.quizarena.poll.PollTestSupport.*;
import static com.quizarena.protocol.MessageTypes.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PollSocketTest {
    @Test
    void dispatchesConcurrentGameplayRequestsReceivedOverLoopbackTcp() throws Exception {
        var loopback = InetAddress.getLoopbackAddress();
        var events = new LinkedBlockingQueue<ProtocolMessage>();
        try (var fixture = new Fixture(); var listener = new ServerSocket(0, 2, loopback);
             var first = new TcpQuizClient(); var second = new TcpQuizClient();
             var subscription = first.subscribe(events::add)) {
            listener.setSoTimeout(5_000);
            try (Socket p1 = connectPeer(first, listener); Socket p2 = connectPeer(second, listener)) {
                p1.setSoTimeout(5_000);
                p2.setSoTimeout(5_000);
                var input1 = new BufferedReader(new InputStreamReader(p1.getInputStream(), StandardCharsets.UTF_8));
                var input2 = new BufferedReader(new InputStreamReader(p2.getInputStream(), StandardCharsets.UTF_8));
                String pollId = fixture.start().pollId();
                var firstVote = first.request(vote("vote-1", pollId, "A"));
                var duplicate = first.request(vote("vote-2", pollId, "A"));
                var otherVote = second.request(vote("vote-3", pollId, "B"));

                // This test-only dispatcher binds player identity to the accepted socket and hands frames to workers.
                var requests = List.of(read(input1), read(input1), read(input2));
                var identities = List.of("p1", "p1", "p2");
                var replies = race(requests.size(), i -> fixture.handle(identities.get(i), requests.get(i)));
                for (ProtocolMessage event : fixture.events) {
                    write(p1, event);
                    write(p2, event);
                }
                write(p1, replies.get(1));
                write(p1, replies.get(0));
                write(p2, replies.get(2));

                ProtocolMessage reply1 = firstVote.get(5, SECONDS);
                ProtocolMessage reply2 = duplicate.get(5, SECONDS);
                assertEquals("vote-1", reply1.requestId());
                assertEquals("vote-2", reply2.requestId());
                assertEquals(1, List.of(reply1, reply2).stream().filter(reply -> "OK".equals(reply.status())).count());
                var rejected = "ERROR".equals(reply1.status()) ? reply1 : reply2;
                assertEquals("ALREADY_VOTED", rejected.errorCode());
                assertEquals(new ProtocolMessage(SUBMIT_VOTE_RESULT, "vote-3", ROOM, "OK", null,
                        Map.of("pollId", pollId, "optionId", "B", "accepted", true)), otherVote.get(5, SECONDS));

                for (ProtocolMessage expected : fixture.events) {
                    assertEquals(expected, events.poll(5, SECONDS));
                }
                assertEquals(Map.of("A", 1, "B", 1, "C", 0), fixture.events.getLast().payload().get("counts"));
            }
        }
    }

    private static Socket connectPeer(TcpQuizClient client, ServerSocket listener) throws Exception {
        client.connect(listener.getInetAddress().getHostAddress(), listener.getLocalPort()).get(5, SECONDS);
        return listener.accept();
    }

    private static ProtocolMessage read(BufferedReader input) throws IOException {
        String line = input.readLine();
        assertNotNull(line, "Test client closed before submitting a vote");
        return MessageCodec.decodeJson(line.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Socket peer, ProtocolMessage message) throws IOException {
        peer.getOutputStream().write(MessageCodec.encodeLine(message));
    }
}
