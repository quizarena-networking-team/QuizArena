package com.quizarena.udp.discovery;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.protocol.MessageTypes;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-side LAN discovery. Listens for {@code DISCOVER} broadcasts and answers each one with a
 * unicast {@code DISCOVER_RESULT} sent to the requester's address and port. Every datagram is
 * reported to the {@link NetworkEventBus}.
 */
public final class DiscoveryResponder implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DiscoveryResponder.class.getName());

    private final DatagramSocket socket;
    private final byte[] advertisement;
    private final NetworkEventBus events;
    private final Thread thread;

    private DiscoveryResponder(DatagramSocket socket, byte[] advertisement, NetworkEventBus events) {
        this.socket = socket;
        this.advertisement = advertisement;
        this.events = events;
        this.thread = new Thread(this::receiveLoop, "quiz-udp-discovery");
        this.thread.setDaemon(true);
    }

    /**
     * Binds the wildcard address (so broadcasts are received) on {@code port}, or an ephemeral
     * port when {@code port} is 0, and starts answering.
     */
    public static DiscoveryResponder start(int port, UdpMessage advertisement, NetworkEventBus events)
            throws SocketException {
        Objects.requireNonNull(advertisement, "advertisement");
        Objects.requireNonNull(events, "events");
        if (!advertisement.is(MessageTypes.DISCOVER_RESULT)) {
            throw new IllegalArgumentException("advertisement must be a DISCOVER_RESULT");
        }
        DiscoveryResponder responder = new DiscoveryResponder(
                new DatagramSocket(port), DatagramCodec.encode(advertisement), events);
        responder.thread.start();
        return responder;
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[DatagramCodec.MAX_DATAGRAM_BYTES + 1];
        while (!socket.isClosed()) {
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(request);
            } catch (IOException closedOrFailed) {
                if (!socket.isClosed()) {
                    events.publish(NetworkEvent.Kind.ERROR, null, null, 0, "UDP discovery receive failed: " + closedOrFailed);
                }
                return;
            }
            answer(request);
        }
    }

    private void answer(DatagramPacket request) {
        String remote = request.getAddress().getHostAddress() + ":" + request.getPort();
        Optional<UdpMessage> message = DatagramCodec.decode(request.getData(), request.getOffset(), request.getLength());
        if (message.isEmpty() || !message.get().is(MessageTypes.DISCOVER)) {
            events.publish(NetworkEvent.Kind.UDP_REJECTED, null, remote, request.getLength(),
                    "discovery port: not a valid DISCOVER datagram");
            return;
        }
        events.publish(NetworkEvent.Kind.UDP_RECEIVED, null, remote, request.getLength(), "DISCOVER");
        try {
            socket.send(new DatagramPacket(advertisement, advertisement.length, request.getSocketAddress()));
            events.publish(NetworkEvent.Kind.UDP_SENT, null, remote, advertisement.length, "DISCOVER_RESULT (unicast)");
        } catch (IOException failure) {
            if (!socket.isClosed()) {
                LOG.log(System.Logger.Level.DEBUG, "DISCOVER_RESULT not sent: " + failure);
                events.publish(NetworkEvent.Kind.ERROR, null, remote, 0, "DISCOVER_RESULT not sent: " + failure);
            }
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
