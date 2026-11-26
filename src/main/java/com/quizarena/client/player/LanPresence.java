package com.quizarena.client.player;

import com.quizarena.udp.UdpConfig;
import com.quizarena.udp.multicast.MulticastInterfaces;
import com.quizarena.udp.multicast.MulticastListener;
import com.quizarena.udp.presence.HeartbeatSender;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Member 4: the player's UDP side-channels for one joined room. It sends UDP heartbeats to the
 * server and listens for the room's multicast announcements.
 *
 * Everything here is advisory. Socket work runs on one background thread, failures are only
 * logged, and nothing here changes the TCP session, which stays authoritative for membership.
 * The session controller calls {@link #start} after JOIN_ROOM succeeds and {@link #stop} when
 * the room is cleared.
 */
final class LanPresence implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LanPresence.class.getName());

    private final UdpConfig config;
    private final ExecutorService worker;
    // Only touched on the worker thread.
    private HeartbeatSender heartbeat;
    private MulticastListener announcements;

    private LanPresence(UdpConfig config) {
        this.config = config;
        this.worker = config == null ? null : Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quiz-lan-presence");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Uses the QUIZ_* UDP settings from the environment, or stays off if they are invalid. */
    static LanPresence fromEnvironment() {
        try {
            return new LanPresence(UdpConfig.fromEnv(System.getenv()));
        } catch (IllegalArgumentException invalid) {
            LOG.log(System.Logger.Level.WARNING, "UDP presence disabled: " + invalid.getMessage());
            return disabled();
        }
    }

    /** A no-op instance, used where no UDP traffic is wanted (for example in UI tests). */
    static LanPresence disabled() {
        return new LanPresence(null);
    }

    /**
     * Starts heartbeats to {@code serverHost} and joins the announcement group for the room.
     * Any previous room's channels are stopped first. {@code onAnnouncement} runs on a
     * background thread.
     */
    void start(String serverHost, String roomCode, String playerId, Consumer<String> onAnnouncement) {
        if (worker == null || worker.isShutdown()) {
            return;
        }
        worker.execute(() -> {
            stopChannels();
            try {
                heartbeat = HeartbeatSender.start(new InetSocketAddress(serverHost, config.heartbeatPort()),
                        roomCode, playerId, config.heartbeatInterval());
            } catch (Exception failure) {
                LOG.log(System.Logger.Level.WARNING, "UDP heartbeat unavailable: " + failure);
            }
            try {
                NetworkInterface nif = MulticastInterfaces.select(config.multicastInterface());
                if (nif == null) {
                    LOG.log(System.Logger.Level.WARNING, "No multicast-capable interface; announcements disabled");
                    return;
                }
                announcements = MulticastListener.join(InetAddress.getByName(config.multicastGroup()),
                        config.multicastPort(), nif, roomCode, message -> onAnnouncement.accept(message.message()));
            } catch (Exception failure) {
                LOG.log(System.Logger.Level.WARNING, "Multicast announcements unavailable: " + failure);
            }
        });
    }

    /** Stops heartbeats and leaves the multicast group. */
    void stop() {
        if (worker != null && !worker.isShutdown()) {
            worker.execute(this::stopChannels);
        }
    }

    private void stopChannels() {
        if (heartbeat != null) {
            heartbeat.close();
            heartbeat = null;
        }
        if (announcements != null) {
            announcements.close();
            announcements = null;
        }
    }

    @Override
    public void close() {
        if (worker != null && !worker.isShutdown()) {
            worker.execute(this::stopChannels);
            worker.shutdown();
        }
    }
}
