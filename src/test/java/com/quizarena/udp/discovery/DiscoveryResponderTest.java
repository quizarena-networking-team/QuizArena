package com.quizarena.udp.discovery;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DiscoveryResponderTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void answersDiscoverWithUnicastAdvertisementAndRecordsEvents() throws Exception {
        NetworkEventBus events = new NetworkEventBus();
        try (DiscoveryResponder responder = DiscoveryResponder.start(0,
                UdpMessage.discoverResult("QuizArena Server", 5000, 8443), events)) {

            List<DiscoveryClient.DiscoveredServer> found =
                    new DiscoveryClient(responder.port(), Duration.ofMillis(600)).discover(Set.of(LOOPBACK));

            assertEquals(List.of(new DiscoveryClient.DiscoveredServer(
                    LOOPBACK.getHostAddress(), 5000, 8443, "QuizArena Server")), found);
            assertEquals(1, events.count(NetworkEvent.Kind.UDP_RECEIVED));
            assertEquals(1, events.count(NetworkEvent.Kind.UDP_SENT));
            assertTrue(events.totalBytes(NetworkEvent.Kind.UDP_SENT) > 0, "real byte count recorded");
        }
    }

    @Test
    void dropsMalformedAndOversizedDatagramsAndKeepsAnswering() throws Exception {
        NetworkEventBus events = new NetworkEventBus();
        try (DiscoveryResponder responder = DiscoveryResponder.start(0,
                UdpMessage.discoverResult("QuizArena Server", 5000, 8443), events);
             DatagramSocket attacker = new DatagramSocket(0, LOOPBACK)) {
            attacker.setSoTimeout(300);
            send(attacker, responder.port(), "garbage".getBytes(StandardCharsets.UTF_8));
            send(attacker, responder.port(), DatagramCodec.encode(UdpMessage.heartbeat("ABC123", "p-1")));
            send(attacker, responder.port(), new byte[DatagramCodec.MAX_DATAGRAM_BYTES + 1]);

            // None of those get an answer...
            assertThrows(java.net.SocketTimeoutException.class,
                    () -> attacker.receive(new DatagramPacket(new byte[1500], 1500)));
            assertEquals(3, events.count(NetworkEvent.Kind.UDP_REJECTED));

            // ...and a valid request afterwards is still answered.
            List<DiscoveryClient.DiscoveredServer> found =
                    new DiscoveryClient(responder.port(), Duration.ofMillis(600)).discover(Set.of(LOOPBACK));
            assertEquals(1, found.size());
        }
    }

    @Test
    void refusesAnAdvertisementOfTheWrongType() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryResponder.start(0, UdpMessage.discover(), new NetworkEventBus()));
    }

    private static void send(DatagramSocket socket, int port, byte[] body) throws Exception {
        socket.send(new DatagramPacket(body, body.length, LOOPBACK, port));
    }
}
