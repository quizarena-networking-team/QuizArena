package com.quizarena.udp.multicast;

import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.StandardSocketOptions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two listeners share one destination port and join the group before anything is sent,
 * mirroring two players on one computer. Skipped (not failed) where no multicast-capable
 * interface exists.
 */
@Timeout(20)
class MulticastListenerTest {

    private static final String GROUP = "239.10.10.10";

    @Test
    void twoJoinedListenersReceiveTheirRoomsAnnouncements() throws Exception {
        List<NetworkInterface> interfaces = MulticastInterfaces.forReceiving(null);
        assumeFalse(interfaces.isEmpty());
        NetworkInterface sendOn = MulticastInterfaces.forSending(null);
        InetAddress group = InetAddress.getByName(GROUP);
        int port = freeUdpPort();

        List<UdpMessage> first = new CopyOnWriteArrayList<>();
        List<UdpMessage> second = new CopyOnWriteArrayList<>();
        try (MulticastListener a = MulticastListener.join(group, port, interfaces, "ABC123", first::add);
             MulticastListener b = MulticastListener.join(group, port, interfaces, "ABC123", second::add);
             MulticastSocket sender = sender(sendOn)) {

            // Both memberships are active before the first send.
            send(sender, group, port, UdpMessage.announcement("OTHER1", "Not for this room"));
            send(sender, group, port, UdpMessage.heartbeat("ABC123", "p-1"));
            send(sender, group, port, UdpMessage.announcement("ABC123", "Round starts now"));

            UdpMessage expected = UdpMessage.announcement("ABC123", "Round starts now");
            awaitSize(first, 1);
            awaitSize(second, 1);
            assertEquals(List.of(expected), first);
            assertEquals(List.of(expected), second);
        }
    }

    /**
     * Regression: group membership is per interface. A listener used to join on one guessed
     * interface, so an announcement sent on any other adapter was silently lost. The listener now
     * joins every candidate interface and must receive whichever one the server sends on.
     */
    @Test
    void receivesAnnouncementsSentOnAnyCandidateInterface() throws Exception {
        List<NetworkInterface> interfaces = MulticastInterfaces.forReceiving(null);
        assumeFalse(interfaces.isEmpty());
        InetAddress group = InetAddress.getByName(GROUP);
        int port = freeUdpPort();

        List<UdpMessage> received = new CopyOnWriteArrayList<>();
        try (MulticastListener listener = MulticastListener.join(group, port, interfaces, "ABC123", received::add)) {
            for (NetworkInterface nif : interfaces) {
                received.clear();
                try (MulticastSocket sender = sender(nif)) {
                    send(sender, group, port, UdpMessage.announcement("ABC123", "via " + nif.getName()));
                }
                awaitSize(received, 1);
                assertFalse(received.isEmpty(), "nothing received when sent on " + nif.getName());
                assertEquals("via " + nif.getName(), received.get(0).message());
            }
        }
    }

    @Test
    void sendingInterfaceIsOneOfTheReceivingCandidates() throws Exception {
        List<NetworkInterface> interfaces = MulticastInterfaces.forReceiving(null);
        assumeFalse(interfaces.isEmpty());
        assertTrue(interfaces.contains(MulticastInterfaces.forSending(null)));
    }

    @Test
    void closeLeavesTheGroupAndStopsDelivery() throws Exception {
        List<NetworkInterface> interfaces = MulticastInterfaces.forReceiving(null);
        assumeFalse(interfaces.isEmpty());
        InetAddress group = InetAddress.getByName(GROUP);
        int port = freeUdpPort();

        List<UdpMessage> received = new CopyOnWriteArrayList<>();
        MulticastListener listener = MulticastListener.join(group, port, interfaces, "ABC123", received::add);
        listener.close();
        listener.close(); // idempotent

        try (MulticastSocket sender = sender(MulticastInterfaces.forSending(null))) {
            send(sender, group, port, UdpMessage.announcement("ABC123", "After close"));
        }
        TimeUnit.MILLISECONDS.sleep(300);
        assertEquals(List.of(), received);
    }

    private static MulticastSocket sender(NetworkInterface nif) throws Exception {
        MulticastSocket sender = new MulticastSocket();
        sender.setNetworkInterface(nif);
        sender.setTimeToLive(1);
        sender.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
        return sender;
    }

    private static void send(MulticastSocket sender, InetAddress group, int port, UdpMessage message) throws Exception {
        byte[] body = DatagramCodec.encode(message);
        sender.send(new DatagramPacket(body, body.length, group, port));
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void assumeFalse(boolean none) {
        assumeTrue(!none, "no multicast-capable network interface");
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (list.size() < size && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        // Give a late, wrongly-delivered datagram a moment to show up before asserting.
        TimeUnit.MILLISECONDS.sleep(200);
    }
}
