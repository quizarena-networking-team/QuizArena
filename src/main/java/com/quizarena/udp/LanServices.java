package com.quizarena.udp;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.quiz.GameEventSink;
import com.quizarena.quiz.PlayerDirectory;
import com.quizarena.udp.discovery.DiscoveryResponder;
import com.quizarena.udp.multicast.AnnouncingEventSink;
import com.quizarena.udp.multicast.MulticastAnnouncer;
import com.quizarena.udp.multicast.MulticastInterfaces;
import com.quizarena.udp.presence.HeartbeatMonitor;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Objects;

/**
 * Member 4's server-side UDP services, started together after the gameplay TCP server:
 * LAN discovery responder, heartbeat monitor and multicast announcer.
 *
 * UDP is supplementary. If one service cannot start (port in use, no multicast interface),
 * the failure is published as an ERROR event and the rest of the server keeps running.
 */
public final class LanServices implements AutoCloseable {

    public static final String SERVER_NAME = "QuizArena Server";

    private final DiscoveryResponder discovery;
    private final HeartbeatMonitor heartbeats;
    private final MulticastAnnouncer announcer;

    private LanServices(DiscoveryResponder discovery, HeartbeatMonitor heartbeats, MulticastAnnouncer announcer) {
        this.discovery = discovery;
        this.heartbeats = heartbeats;
        this.announcer = announcer;
    }

    /**
     * @param tcpPort the gameplay port actually bound by the NIO server (advertised to players)
     * @param players the authoritative room membership, read-only here
     */
    public static LanServices start(UdpConfig config, int tcpPort, PlayerDirectory players, NetworkEventBus events) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(events, "events");

        DiscoveryResponder discovery = null;
        try {
            discovery = DiscoveryResponder.start(config.discoveryPort(),
                    UdpMessage.discoverResult(SERVER_NAME, tcpPort, config.tlsPort()), events);
            events.publish(NetworkEvent.Kind.SERVER_STARTED, null, "udp:" + discovery.port(), 0,
                    "UDP discovery responder listening");
        } catch (Exception failure) {
            events.publish(NetworkEvent.Kind.ERROR, null, null, 0, "UDP discovery unavailable: " + failure);
        }

        HeartbeatMonitor heartbeats = null;
        try {
            heartbeats = HeartbeatMonitor.start(config.heartbeatPort(), players, events, config.heartbeatStaleAfter());
            events.publish(NetworkEvent.Kind.SERVER_STARTED, null, "udp:" + heartbeats.port(), 0,
                    "UDP heartbeat monitor listening");
        } catch (Exception failure) {
            events.publish(NetworkEvent.Kind.ERROR, null, null, 0, "UDP heartbeat monitor unavailable: " + failure);
        }

        MulticastAnnouncer announcer = null;
        try {
            NetworkInterface nif = MulticastInterfaces.forSending(config.multicastInterface());
            if (nif == null) {
                throw new IllegalStateException("no multicast-capable network interface");
            }
            announcer = MulticastAnnouncer.open(InetAddress.getByName(config.multicastGroup()),
                    config.multicastPort(), nif, events);
            events.publish(NetworkEvent.Kind.SERVER_STARTED, null,
                    config.multicastGroup() + ":" + config.multicastPort(), 0,
                    "multicast announcements on interface " + nif.getName() + " (TTL 1)");
        } catch (Exception failure) {
            events.publish(NetworkEvent.Kind.ERROR, null, null, 0, "multicast announcements unavailable: " + failure);
        }
        return new LanServices(discovery, heartbeats, announcer);
    }

    /** Sends a best-effort multicast announcement to a room's players. Never blocks. */
    public void announce(String roomCode, String message) {
        if (announcer != null) {
            announcer.announce(roomCode, message);
        }
    }

    /**
     * Wraps a room's event sink so game phases are also announced over multicast. Use it where the
     * room's Poll/Quiz services are created: {@code lan.wrap(rooms.eventSink(roomCode))}.
     */
    public GameEventSink wrap(GameEventSink sink) {
        return new AnnouncingEventSink(sink, this::announce);
    }

    /** Bound discovery port, or -1 if discovery is unavailable. */
    public int discoveryPort() {
        return discovery == null ? -1 : discovery.port();
    }

    /** Bound heartbeat port, or -1 if the heartbeat monitor is unavailable. */
    public int heartbeatPort() {
        return heartbeats == null ? -1 : heartbeats.port();
    }

    public boolean multicastAvailable() {
        return announcer != null;
    }

    @Override
    public void close() {
        if (discovery != null) {
            discovery.close();
        }
        if (heartbeats != null) {
            heartbeats.close();
        }
        if (announcer != null) {
            announcer.close();
        }
    }
}
