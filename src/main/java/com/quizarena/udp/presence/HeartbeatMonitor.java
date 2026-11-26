package com.quizarena.udp.presence;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.protocol.MessageTypes;
import com.quizarena.quiz.PlayerDirectory;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Server-side presence monitor for UDP {@code HEARTBEAT} datagrams.
 *
 * Heartbeats are advisory. This class only <em>reads</em> the authoritative room membership
 * ({@link PlayerDirectory}) and only publishes warnings to the {@link NetworkEventBus}:
 * <ul>
 *   <li>a heartbeat is accepted only for a player the TCP session layer says is in that room;</li>
 *   <li>after {@code staleAfter} without heartbeats, one {@code PRESENCE_WARNING} is published,
 *       and one more when heartbeats resume;</li>
 *   <li>it never removes a player. When TCP membership ends, tracking is simply dropped.</li>
 * </ul>
 */
public final class HeartbeatMonitor implements AutoCloseable {

    private record Key(String roomCode, String playerId) {
    }

    private static final class Presence {
        long lastSeenNanos;
        String remote;
        boolean stale;
    }

    private final PlayerDirectory players;
    private final NetworkEventBus events;
    private final long staleAfterNanos;
    private final LongSupplier clock;
    private final Map<Key, Presence> presence = new HashMap<>();
    private DatagramSocket socket;
    private ScheduledExecutorService sweeper;

    HeartbeatMonitor(PlayerDirectory players, NetworkEventBus events, Duration staleAfter, LongSupplier nanoClock) {
        this.players = Objects.requireNonNull(players, "players");
        this.events = Objects.requireNonNull(events, "events");
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        this.staleAfterNanos = staleAfter.toNanos();
        this.clock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * Binds {@code port} (0 for ephemeral), receives heartbeats on a daemon thread and checks for
     * silent players once per second.
     */
    public static HeartbeatMonitor start(int port, PlayerDirectory players, NetworkEventBus events, Duration staleAfter)
            throws SocketException {
        HeartbeatMonitor monitor = new HeartbeatMonitor(players, events, staleAfter, System::nanoTime);
        monitor.socket = new DatagramSocket(port);
        Thread receiver = new Thread(monitor::receiveLoop, "quiz-udp-heartbeat");
        receiver.setDaemon(true);
        receiver.start();
        monitor.sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quiz-heartbeat-sweep");
            thread.setDaemon(true);
            return thread;
        });
        monitor.sweeper.scheduleWithFixedDelay(monitor::sweep, 1, 1, TimeUnit.SECONDS);
        return monitor;
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[DatagramCodec.MAX_DATAGRAM_BYTES + 1];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException closedOrFailed) {
                if (!socket.isClosed()) {
                    events.publish(NetworkEvent.Kind.ERROR, null, null, 0, "UDP heartbeat receive failed: " + closedOrFailed);
                }
                return;
            }
            String remote = packet.getAddress().getHostAddress() + ":" + packet.getPort();
            record(DatagramCodec.decode(packet.getData(), packet.getOffset(), packet.getLength()), remote,
                    packet.getLength());
        }
    }

    /** Handles one received datagram. Package-private for tests. */
    synchronized void record(Optional<UdpMessage> decoded, String remote, int bytes) {
        if (decoded.isEmpty() || !decoded.get().is(MessageTypes.HEARTBEAT)) {
            events.publish(NetworkEvent.Kind.UDP_REJECTED, null, remote, bytes, "heartbeat port: not a valid HEARTBEAT");
            return;
        }
        UdpMessage heartbeat = decoded.get();
        String room = heartbeat.roomCode();
        String playerId = heartbeat.playerId();
        if (players.find(room, playerId).isEmpty()) {
            // Heartbeats do not prove identity: only players the TCP session layer knows are tracked.
            events.publish(NetworkEvent.Kind.UDP_REJECTED, null, remote, bytes,
                    "HEARTBEAT for a player not in room " + room);
            return;
        }
        events.publish(NetworkEvent.Kind.UDP_RECEIVED, shortId(playerId), remote, bytes, "HEARTBEAT room=" + room);
        Presence state = presence.computeIfAbsent(new Key(room, playerId), key -> new Presence());
        state.lastSeenNanos = clock.getAsLong();
        state.remote = remote;
        if (state.stale) {
            state.stale = false;
            events.publish(NetworkEvent.Kind.PRESENCE_WARNING, shortId(playerId), remote, 0,
                    "heartbeat resumed for a player in room " + room);
        }
    }

    /** Publishes one warning per player that went silent. Package-private for tests. */
    synchronized void sweep() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<Key, Presence>> entries = presence.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, Presence> entry = entries.next();
            Key key = entry.getKey();
            Presence state = entry.getValue();
            if (players.find(key.roomCode(), key.playerId()).isEmpty()) {
                entries.remove(); // TCP membership ended; that decision is not ours.
            } else if (!state.stale && now - state.lastSeenNanos > staleAfterNanos) {
                state.stale = true;
                events.publish(NetworkEvent.Kind.PRESENCE_WARNING, shortId(key.playerId()), state.remote, 0,
                        "no heartbeat for " + TimeUnit.NANOSECONDS.toSeconds(now - state.lastSeenNanos)
                                + " s in room " + key.roomCode() + "; TCP session still connected, player kept");
            }
        }
    }

    /** Number of players whose heartbeats are currently being tracked. */
    synchronized int trackedPlayers() {
        return presence.size();
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        if (socket != null) {
            socket.close();
        }
    }

    private static String shortId(String playerId) {
        return playerId.length() <= 8 ? playerId : playerId.substring(0, 8);
    }
}
