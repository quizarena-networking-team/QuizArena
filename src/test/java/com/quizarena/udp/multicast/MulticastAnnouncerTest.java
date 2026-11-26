package com.quizarena.udp.multicast;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.udp.UdpMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Server announcer to two player listeners on one shared port (T09). Both listeners have joined
 * the group before the announcement is sent. Skipped where no multicast interface exists.
 */
@Timeout(15)
class MulticastAnnouncerTest {

    @Test
    void twoSubscribedClientsReceiveTheServersAnnouncement() throws Exception {
        NetworkInterface nif = MulticastInterfaces.select(null);
        assumeTrue(nif != null, "no multicast-capable network interface");
        InetAddress group = InetAddress.getByName("239.10.10.10");
        int port = freeUdpPort();
        NetworkEventBus events = new NetworkEventBus();

        List<UdpMessage> first = new CopyOnWriteArrayList<>();
        List<UdpMessage> second = new CopyOnWriteArrayList<>();
        try (MulticastListener a = MulticastListener.join(group, port, nif, "ABC123", first::add);
             MulticastListener b = MulticastListener.join(group, port, nif, "ABC123", second::add);
             MulticastAnnouncer announcer = MulticastAnnouncer.open(group, port, nif, events)) {

            announcer.announce("ABC123", "Round starts now");

            awaitSize(first, 1);
            awaitSize(second, 1);
            UdpMessage expected = UdpMessage.announcement("ABC123", "Round starts now");
            assertEquals(List.of(expected), first);
            assertEquals(List.of(expected), second);
            assertEquals(1, events.count(NetworkEvent.Kind.UDP_SENT), "the server records the real datagram");
            assertTrue(events.recent().get(events.recent().size() - 1).detail().contains("ANNOUNCEMENT"));
        }
    }

    @Test
    void longTextIsShortenedAndBlankInputIgnored() throws Exception {
        NetworkInterface nif = MulticastInterfaces.select(null);
        assumeTrue(nif != null, "no multicast-capable network interface");
        InetAddress group = InetAddress.getByName("239.10.10.10");
        int port = freeUdpPort();
        NetworkEventBus events = new NetworkEventBus();

        List<UdpMessage> received = new CopyOnWriteArrayList<>();
        try (MulticastListener listener = MulticastListener.join(group, port, nif, "ABC123", received::add);
             MulticastAnnouncer announcer = MulticastAnnouncer.open(group, port, nif, events)) {
            announcer.announce("ABC123", " ");
            announcer.announce(null, "No room");
            announcer.announce("ABC123", "x".repeat(500));

            awaitSize(received, 1);
            assertEquals(1, received.size());
            assertEquals(MulticastAnnouncer.MAX_MESSAGE_CHARS, received.get(0).message().length());
        }
    }

    @Test
    void rejectsANonMulticastGroup() throws Exception {
        NetworkInterface nif = MulticastInterfaces.select(null);
        assumeTrue(nif != null, "no multicast-capable network interface");
        assertThrows(IllegalArgumentException.class, () ->
                MulticastAnnouncer.open(InetAddress.getByName("10.0.0.1"), 5003, nif, new NetworkEventBus()));
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (list.size() < size && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        TimeUnit.MILLISECONDS.sleep(200);
    }
}
