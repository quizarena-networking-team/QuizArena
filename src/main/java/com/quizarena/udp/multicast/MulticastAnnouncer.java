package com.quizarena.udp.multicast;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.StandardSocketOptions;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Server-side multicast sender for supplementary {@code ANNOUNCEMENT} hints.
 *
 * Datagrams use TTL 1 so they stay on the local network. {@link #announce} only queues the
 * message for one background sender thread, so it is safe to call while holding game locks.
 * Announcements never carry answers, options or scores; those stay on reliable TCP.
 */
public final class MulticastAnnouncer implements AutoCloseable {

    /** Longest announcement text sent; longer text is shortened. */
    public static final int MAX_MESSAGE_CHARS = 200;

    private final MulticastSocket socket;
    private final InetAddress group;
    private final int port;
    private final NetworkEventBus events;
    private final ExecutorService sender;

    private MulticastAnnouncer(MulticastSocket socket, InetAddress group, int port, NetworkEventBus events) {
        this.socket = socket;
        this.group = group;
        this.port = port;
        this.events = events;
        this.sender = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quiz-udp-multicast");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens a sending socket for {@code group:port} on {@code nif}. Multicast loopback is
     * enabled so player windows on the server's own computer also receive the announcements.
     */
    public static MulticastAnnouncer open(InetAddress group, int port, NetworkInterface nif, NetworkEventBus events)
            throws IOException {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(nif, "nif");
        Objects.requireNonNull(events, "events");
        if (!group.isMulticastAddress()) {
            throw new IllegalArgumentException(group + " is not a multicast address");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        MulticastSocket socket = new MulticastSocket();
        try {
            socket.setTimeToLive(1);
            socket.setNetworkInterface(nif);
            socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
        return new MulticastAnnouncer(socket, group, port, events);
    }

    /** Queues one announcement for a room. Returns immediately; delivery is best effort. */
    public void announce(String roomCode, String message) {
        if (roomCode == null || roomCode.isBlank() || message == null || message.isBlank()) {
            return;
        }
        String text = message.strip();
        if (text.length() > MAX_MESSAGE_CHARS) {
            text = text.substring(0, MAX_MESSAGE_CHARS - 1) + "…";
        }
        UdpMessage announcement = UdpMessage.announcement(roomCode.strip(), text);
        try {
            sender.execute(() -> send(announcement));
        } catch (RejectedExecutionException closed) {
            // Announcer already closed; announcements are optional.
        }
    }

    private void send(UdpMessage announcement) {
        String target = group.getHostAddress() + ":" + port;
        try {
            byte[] body = DatagramCodec.encode(announcement);
            socket.send(new DatagramPacket(body, body.length, group, port));
            events.publish(NetworkEvent.Kind.UDP_SENT, null, target, body.length,
                    "ANNOUNCEMENT multicast room=" + announcement.roomCode() + " \"" + announcement.message() + "\"");
        } catch (IOException | IllegalArgumentException failure) {
            if (!socket.isClosed()) {
                events.publish(NetworkEvent.Kind.ERROR, null, target, 0, "multicast announcement not sent: " + failure);
            }
        }
    }

    @Override
    public void close() {
        sender.shutdown();
        socket.close();
    }
}
