package com.quizarena.udp;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.server.RoomRegistry;
import com.quizarena.udp.discovery.DiscoveryClient;
import com.quizarena.udp.multicast.MulticastInterfaces;
import com.quizarena.udp.multicast.MulticastListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.quizarena.protocol.MessageTypes.QUESTION_STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real UDP services, started the way ServerMain starts them, on ephemeral ports. */
@Timeout(20)
class LanServicesTest {

    @Test
    void advertisesTheBoundTcpPortAndClosesCleanly() throws Exception {
        NetworkEventBus events = new NetworkEventBus();
        UdpConfig config = testConfig(freeUdpPort());
        try (LanServices lan = LanServices.start(config, 5123, new RoomRegistry(), events)) {
            assertTrue(lan.discoveryPort() > 0);
            assertTrue(lan.heartbeatPort() > 0);

            List<DiscoveryClient.DiscoveredServer> found = new DiscoveryClient(lan.discoveryPort(), Duration.ofMillis(600))
                    .discover(Set.of(InetAddress.getLoopbackAddress()));
            assertEquals(1, found.size());
            assertEquals(5123, found.get(0).tcpPort());
            assertEquals(8443, found.get(0).tlsPort());
        }
        // Multicast may be unavailable on some machines; discovery and heartbeat must have started.
        assertTrue(events.recent().stream().noneMatch(event -> event.kind() == NetworkEvent.Kind.ERROR
                && (event.detail().contains("discovery") || event.detail().contains("heartbeat"))),
                events.recent().toString());
    }

    @Test
    void wrappedRoomSinkAnnouncesGamePhasesToJoinedPlayers() throws Exception {
        List<NetworkInterface> interfaces = MulticastInterfaces.forReceiving(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(!interfaces.isEmpty(), "no multicast-capable network interface");
        int multicastPort = freeUdpPort();
        UdpConfig config = testConfig(multicastPort);
        List<ProtocolMessage> tcp = new CopyOnWriteArrayList<>();
        List<UdpMessage> received = new CopyOnWriteArrayList<>();

        try (LanServices lan = LanServices.start(config, 5000, new RoomRegistry(), new NetworkEventBus());
             MulticastListener player = MulticastListener.join(InetAddress.getByName(config.multicastGroup()),
                     multicastPort, interfaces, "ABC123", received::add)) {
            assertTrue(lan.multicastAvailable());

            lan.wrap(tcp::add).broadcast(new ProtocolMessage(QUESTION_STARTED, null, "ABC123", null, null, Map.of()));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (received.isEmpty() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertEquals(1, tcp.size());
            assertEquals(List.of(UdpMessage.announcement("ABC123", "A new question has started.")), received);
        }
    }

    @Test
    void aPortConflictIsReportedAndTheOtherServicesStillStart() throws Exception {
        NetworkEventBus events = new NetworkEventBus();
        try (DatagramSocket taken = new DatagramSocket(0)) {
            UdpConfig defaults = testConfig(freeUdpPort());
            UdpConfig config = new UdpConfig(taken.getLocalPort(), 0, defaults.multicastGroup(),
                    defaults.multicastPort(), null, 8443, defaults.heartbeatInterval(), defaults.heartbeatStaleAfter());
            try (LanServices lan = LanServices.start(config, 5000, new RoomRegistry(), events)) {
                assertEquals(-1, lan.discoveryPort());
                assertTrue(lan.heartbeatPort() > 0);
                assertTrue(events.count(NetworkEvent.Kind.ERROR) >= 1);
            }
        }
    }

    private static UdpConfig testConfig(int multicastPort) {
        UdpConfig defaults = UdpConfig.defaults();
        return new UdpConfig(0, 0, defaults.multicastGroup(), multicastPort, null, 8443,
                defaults.heartbeatInterval(), defaults.heartbeatStaleAfter());
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
