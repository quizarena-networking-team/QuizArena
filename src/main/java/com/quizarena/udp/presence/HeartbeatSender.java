package com.quizarena.udp.presence;

import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Player-side presence signal: sends one {@code HEARTBEAT} datagram to the server at a fixed
 * interval while the player is in a room.
 *
 * Heartbeats are advisory only. A lost datagram is never retried, and a send failure is
 * logged and ignored; the TCP session alone decides whether the player is connected.
 */
public final class HeartbeatSender implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(HeartbeatSender.class.getName());

    private final DatagramSocket socket;
    private final DatagramPacket packet;
    private final ScheduledExecutorService timer;

    private HeartbeatSender(InetSocketAddress server, String roomCode, String playerId) throws SocketException {
        byte[] body = DatagramCodec.encode(UdpMessage.heartbeat(roomCode, playerId));
        this.socket = new DatagramSocket();
        this.packet = new DatagramPacket(body, body.length, server);
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quiz-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts sending immediately, then once per {@code interval}, until {@link #close()}. */
    public static HeartbeatSender start(InetSocketAddress server, String roomCode, String playerId, Duration interval)
            throws SocketException {
        Objects.requireNonNull(server, "server");
        requireText(roomCode, "roomCode");
        requireText(playerId, "playerId");
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        HeartbeatSender sender = new HeartbeatSender(server, roomCode, playerId);
        sender.timer.scheduleAtFixedRate(sender::sendOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        return sender;
    }

    private void sendOnce() {
        try {
            socket.send(packet);
        } catch (IOException failure) {
            if (!socket.isClosed()) {
                LOG.log(System.Logger.Level.DEBUG, "Heartbeat not sent: " + failure);
            }
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        socket.close();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
