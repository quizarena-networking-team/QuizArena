package com.quizarena.udp.discovery;

import com.quizarena.protocol.MessageTypes;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Player-side LAN discovery. Broadcasts one {@code DISCOVER} datagram and collects the
 * unicast {@code DISCOVER_RESULT} replies that arrive before the timeout.
 *
 * {@link #discover()} blocks for the whole timeout, so never call it on the JavaFX thread.
 * An empty result is normal (broadcast blocked, no server); the player then enters the IP manually.
 */
public final class DiscoveryClient {

    /** A server that answered; {@code host} is the source address of its reply. */
    public record DiscoveredServer(String host, int tcpPort, int tlsPort, String serverName) {
    }

    private static final System.Logger LOG = System.getLogger(DiscoveryClient.class.getName());

    private final int discoveryPort;
    private final Duration timeout;

    public DiscoveryClient(int discoveryPort, Duration timeout) {
        if (discoveryPort < 1 || discoveryPort > 65_535) {
            throw new IllegalArgumentException("discoveryPort must be between 1 and 65535");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.discoveryPort = discoveryPort;
        this.timeout = timeout;
    }

    /** Broadcasts on the LAN (and probes localhost so a same-machine server is found too). */
    public List<DiscoveredServer> discover() throws IOException {
        return discover(defaultTargets());
    }

    /** Sends {@code DISCOVER} to each target address and collects the replies until the timeout. */
    public List<DiscoveredServer> discover(Set<InetAddress> targets) throws IOException {
        Objects.requireNonNull(targets, "targets");
        byte[] request = DatagramCodec.encode(UdpMessage.discover());
        Map<String, DiscoveredServer> found = new LinkedHashMap<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            int sent = 0;
            for (InetAddress target : targets) {
                try {
                    socket.send(new DatagramPacket(request, request.length, target, discoveryPort));
                    sent++;
                } catch (IOException unreachable) {
                    // One interface refusing a broadcast must not stop the others.
                    LOG.log(System.Logger.Level.DEBUG, "DISCOVER not sent to " + target + ": " + unreachable);
                }
            }
            if (sent == 0) {
                throw new IOException("No discovery request could be sent");
            }

            byte[] buffer = new byte[DatagramCodec.MAX_DATAGRAM_BYTES];
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
                if (remainingMillis <= 0) {
                    break;
                }
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException done) {
                    break;
                }
                DatagramCodec.decode(reply.getData(), reply.getOffset(), reply.getLength())
                        .filter(message -> message.is(MessageTypes.DISCOVER_RESULT))
                        .filter(message -> validPort(message.tcpPort()))
                        .ifPresent(message -> {
                            String host = reply.getAddress().getHostAddress();
                            found.putIfAbsent(host + ":" + message.tcpPort(), new DiscoveredServer(host,
                                    message.tcpPort(),
                                    validPort(message.tlsPort()) ? message.tlsPort() : 0,
                                    message.serverName() == null ? "QuizArena Server" : message.serverName()));
                        });
            }
        }
        return List.copyOf(new ArrayList<>(found.values()));
    }

    /**
     * Limited broadcast, each interface's directed broadcast address (more reliable on
     * multi-adapter Windows machines), and loopback for a server on the same computer.
     */
    static Set<InetAddress> defaultTargets() throws SocketException {
        Set<InetAddress> targets = new LinkedHashSet<>();
        targets.add(InetAddress.getLoopbackAddress());
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) {
                continue;
            }
            for (InterfaceAddress address : nif.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) {
                    targets.add(address.getBroadcast());
                }
            }
        }
        return targets;
    }

    private static boolean validPort(Integer port) {
        return port != null && port >= 1 && port <= 65_535;
    }
}
