package com.quizarena.udp.discovery;

import com.quizarena.protocol.MessageTypes;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real UDP on loopback, against a minimal test responder bound to an ephemeral port. */
@Timeout(10)
class DiscoveryClientTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private DatagramSocket responder;
    private Thread responderThread;

    @AfterEach
    void stopResponder() throws InterruptedException {
        if (responder != null) {
            responder.close();
            responderThread.join(2000);
        }
    }

    @Test
    void findsAServerThatAnswersOverUnicast() throws Exception {
        AtomicInteger requests = startResponder(1, false);

        List<DiscoveryClient.DiscoveredServer> found = client().discover(Set.of(LOOPBACK));

        assertEquals(List.of(new DiscoveryClient.DiscoveredServer(
                LOOPBACK.getHostAddress(), 5000, 8443, "QuizArena Server")), found);
        assertEquals(1, requests.get());
    }

    @Test
    void ignoresInvalidRepliesAndRemovesDuplicates() throws Exception {
        startResponder(3, true);

        List<DiscoveryClient.DiscoveredServer> found = client().discover(Set.of(LOOPBACK));

        assertEquals(1, found.size(), "garbage dropped and the duplicate answer merged");
        assertEquals(5000, found.get(0).tcpPort());
    }

    @Test
    void returnsAnEmptyListWhenNoServerAnswers() throws Exception {
        int unusedPort;
        try (DatagramSocket probe = new DatagramSocket(0, LOOPBACK)) {
            unusedPort = probe.getLocalPort();
        }
        long start = System.nanoTime();

        List<DiscoveryClient.DiscoveredServer> found =
                new DiscoveryClient(unusedPort, Duration.ofMillis(300)).discover(Set.of(LOOPBACK));

        assertTrue(found.isEmpty(), "the player falls back to entering the IP manually");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
    }

    @Test
    void defaultTargetsIncludeBroadcastAndLoopback() throws Exception {
        Set<InetAddress> targets = DiscoveryClient.defaultTargets();
        assertTrue(targets.contains(InetAddress.getByName("255.255.255.255")));
        assertTrue(targets.contains(LOOPBACK));
    }

    private DiscoveryClient client() {
        return new DiscoveryClient(responder.getLocalPort(), Duration.ofMillis(600));
    }

    /**
     * Answers each DISCOVER with one DISCOVER_RESULT. With {@code noisy}, it first sends a
     * malformed datagram, a different message type and then the valid answer twice.
     */
    private AtomicInteger startResponder(int replyCount, boolean noisy) throws IOException {
        responder = new DatagramSocket(0, LOOPBACK);
        AtomicInteger requests = new AtomicInteger();
        responderThread = new Thread(() -> {
            byte[] buffer = new byte[DatagramCodec.MAX_DATAGRAM_BYTES];
            while (!responder.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    responder.receive(packet);
                    boolean discover = DatagramCodec.decode(packet.getData(), packet.getOffset(), packet.getLength())
                            .filter(message -> message.is(MessageTypes.DISCOVER)).isPresent();
                    if (!discover) {
                        continue;
                    }
                    requests.incrementAndGet();
                    if (noisy) {
                        reply(packet, "not json".getBytes(StandardCharsets.UTF_8));
                        reply(packet, DatagramCodec.encode(UdpMessage.heartbeat("X", "Y")));
                        reply(packet, DatagramCodec.encode(UdpMessage.discoverResult("Bad", 0, 8443)));
                    }
                    byte[] answer = DatagramCodec.encode(UdpMessage.discoverResult("QuizArena Server", 5000, 8443));
                    for (int i = 0; i < replyCount; i++) {
                        reply(packet, answer);
                    }
                } catch (IOException closed) {
                    return;
                }
            }
        }, "test-discovery-responder");
        responderThread.start();
        return requests;
    }

    private void reply(DatagramPacket request, byte[] body) throws IOException {
        responder.send(new DatagramPacket(body, body.length, request.getSocketAddress()));
    }
}
