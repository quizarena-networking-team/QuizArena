package com.quizarena.udp.presence;

import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.quiz.PlayerDirectory;
import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class HeartbeatMonitorTest {

    private static final String ROOM = "ABC123";
    private static final String PLAYER = "p-101";

    /** Stand-in for the authoritative TCP room membership (RoomRegistry). Read-only to the monitor. */
    private final Map<String, PlayerDirectory.Player> members = new ConcurrentHashMap<>(
            Map.of(PLAYER, new PlayerDirectory.Player(PLAYER, "Nimal")));
    private final PlayerDirectory directory = new PlayerDirectory() {
        @Override
        public Optional<PlayerDirectory.Player> find(String roomCode, String playerId) {
            return ROOM.equals(roomCode) && playerId != null ? Optional.ofNullable(members.get(playerId)) : Optional.empty();
        }

        @Override
        public List<PlayerDirectory.Player> players(String roomCode) {
            return ROOM.equals(roomCode) ? List.copyOf(members.values()) : List.of();
        }
    };
    private final NetworkEventBus events = new NetworkEventBus();
    private final AtomicLong now = new AtomicLong();
    private final HeartbeatMonitor monitor = new HeartbeatMonitor(directory, events, Duration.ofSeconds(6), now::get);

    @Test
    void freshHeartbeatsRaiseNoWarning() {
        beat(PLAYER);
        advance(4);
        monitor.sweep();
        beat(PLAYER);
        advance(4);
        monitor.sweep();

        assertEquals(0, events.count(NetworkEvent.Kind.PRESENCE_WARNING));
        assertEquals(2, events.count(NetworkEvent.Kind.UDP_RECEIVED));
    }

    @Test
    void silenceWarnsOnceButNeverRemovesTheTcpMember() {
        beat(PLAYER);
        advance(7);
        monitor.sweep();
        advance(10);
        monitor.sweep();

        assertEquals(1, events.count(NetworkEvent.Kind.PRESENCE_WARNING), "one warning, not one per sweep");
        assertTrue(directory.find(ROOM, PLAYER).isPresent(), "TCP membership is untouched");
        assertEquals(1, monitor.trackedPlayers());
        NetworkEvent warning = events.recent().get(events.recent().size() - 1);
        assertTrue(warning.detail().contains("player kept"), warning.detail());
    }

    @Test
    void resumedHeartbeatReportsRecoveryAndCanWarnAgain() {
        beat(PLAYER);
        advance(7);
        monitor.sweep();
        beat(PLAYER);
        assertEquals(2, events.count(NetworkEvent.Kind.PRESENCE_WARNING), "stale + resumed");

        advance(7);
        monitor.sweep();
        assertEquals(3, events.count(NetworkEvent.Kind.PRESENCE_WARNING));
    }

    @Test
    void heartbeatsForUnknownPlayersOrRoomsAreRejected() {
        beat("forged-id");
        monitor.record(Optional.of(UdpMessage.heartbeat("OTHER1", PLAYER)), "10.0.0.9:4000", 60);
        monitor.record(Optional.empty(), "10.0.0.9:4000", 12);
        monitor.record(Optional.of(UdpMessage.discover()), "10.0.0.9:4000", 30);

        assertEquals(4, events.count(NetworkEvent.Kind.UDP_REJECTED));
        assertEquals(0, events.count(NetworkEvent.Kind.UDP_RECEIVED));
        assertEquals(0, monitor.trackedPlayers());
    }

    @Test
    void whenTcpMembershipEndsTrackingIsDroppedWithoutAWarning() {
        beat(PLAYER);
        members.remove(PLAYER); // the TCP session layer removed the player
        advance(30);
        monitor.sweep();

        assertEquals(0, monitor.trackedPlayers());
        assertEquals(0, events.count(NetworkEvent.Kind.PRESENCE_WARNING));
    }

    @Test
    void receivesRealHeartbeatDatagrams() throws Exception {
        NetworkEventBus liveEvents = new NetworkEventBus();
        try (HeartbeatMonitor live = HeartbeatMonitor.start(0, directory, liveEvents, Duration.ofSeconds(6));
             DatagramSocket player = new DatagramSocket()) {
            byte[] body = DatagramCodec.encode(UdpMessage.heartbeat(ROOM, PLAYER));
            player.send(new DatagramPacket(body, body.length, InetAddress.getLoopbackAddress(), live.port()));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (liveEvents.count(NetworkEvent.Kind.UDP_RECEIVED) == 0 && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertEquals(1, liveEvents.count(NetworkEvent.Kind.UDP_RECEIVED));
            assertEquals(body.length, liveEvents.totalBytes(NetworkEvent.Kind.UDP_RECEIVED));
        }
    }

    private void beat(String playerId) {
        monitor.record(Optional.of(UdpMessage.heartbeat(ROOM, playerId)), "127.0.0.1:50000", 70);
    }

    private void advance(long seconds) {
        now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }
}
