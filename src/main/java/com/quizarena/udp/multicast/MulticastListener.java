package com.quizarena.udp.multicast;

import com.quizarena.protocol.MessageTypes;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Player-side multicast receiver. Joins the announcement group on one network interface and
 * delivers {@code ANNOUNCEMENT} messages for the player's room to a callback.
 *
 * Announcements are supplementary hints only; missing one never means a game action did not
 * happen. The callback runs on the listener's daemon thread, so JavaFX code must hand off with
 * {@code Platform.runLater}.
 */
public final class MulticastListener implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MulticastListener.class.getName());

    private final MulticastSocket socket;
    private final InetSocketAddress group;
    private final NetworkInterface nif;
    private final String roomCode;
    private final Consumer<UdpMessage> onAnnouncement;
    private final Thread thread;

    private MulticastListener(MulticastSocket socket, InetSocketAddress group, NetworkInterface nif,
                              String roomCode, Consumer<UdpMessage> onAnnouncement) {
        this.socket = socket;
        this.group = group;
        this.nif = nif;
        this.roomCode = roomCode;
        this.onAnnouncement = onAnnouncement;
        this.thread = new Thread(this::receiveLoop, "quiz-multicast-listener");
        this.thread.setDaemon(true);
    }

    /**
     * Binds the multicast port, joins the group on {@code nif}, and starts receiving.
     * When this method returns, the group membership is active.
     */
    public static MulticastListener join(InetAddress groupAddress, int port, NetworkInterface nif,
                                         String roomCode, Consumer<UdpMessage> onAnnouncement) throws IOException {
        Objects.requireNonNull(groupAddress, "groupAddress");
        Objects.requireNonNull(nif, "nif");
        Objects.requireNonNull(onAnnouncement, "onAnnouncement");
        if (!groupAddress.isMulticastAddress()) {
            throw new IllegalArgumentException(groupAddress + " is not a multicast address");
        }
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("roomCode is required");
        }
        // MulticastSocket enables SO_REUSEADDR, so several players on one computer can share the port.
        MulticastSocket socket = new MulticastSocket(port);
        InetSocketAddress group = new InetSocketAddress(groupAddress, 0);
        try {
            socket.joinGroup(group, nif);
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
        MulticastListener listener = new MulticastListener(socket, group, nif, roomCode, onAnnouncement);
        listener.thread.start();
        return listener;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[DatagramCodec.MAX_DATAGRAM_BYTES];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException closedOrFailed) {
                if (!socket.isClosed()) {
                    LOG.log(System.Logger.Level.WARNING, "Multicast receive failed: " + closedOrFailed);
                }
                return;
            }
            DatagramCodec.decode(packet.getData(), packet.getOffset(), packet.getLength())
                    .filter(message -> message.is(MessageTypes.ANNOUNCEMENT))
                    .filter(message -> roomCode.equals(message.roomCode()))
                    .filter(message -> message.message() != null && !message.message().isBlank())
                    .ifPresent(this::deliver);
        }
    }

    private void deliver(UdpMessage message) {
        try {
            onAnnouncement.accept(message);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Announcement listener failed", failure);
        }
    }

    /** Leaves the group and stops receiving. */
    @Override
    public void close() {
        if (socket.isClosed()) {
            return;
        }
        try {
            socket.leaveGroup(group, nif);
        } catch (IOException ignored) {
            // Closing the socket drops the membership anyway.
        }
        socket.close();
    }
}
