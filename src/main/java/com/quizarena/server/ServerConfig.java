package com.quizarena.server;

import java.time.Duration;
import java.util.Map;

/**
 * Runtime settings for the gameplay server. Environment variable names follow
 * the architecture blueprint (Appendix C); no secrets are involved.
 *
 * <pre>
 * QUIZ_BIND_HOST             default 0.0.0.0
 * QUIZ_TCP_PORT              default 5000
 * QUIZ_MAX_CONNECTIONS       default 256
 * QUIZ_MAX_PLAYERS           default 32   (players per room)
 * QUIZ_IDLE_TIMEOUT_SECONDS  default 1800 (0 disables the idle sweep)
 * QUIZ_DEV_ROOM              optional room code to pre-create for manual testing
 * QUIZ_LOG_IO                default true (print READ/WRITE events to the console)
 * </pre>
 */
public record ServerConfig(
        String bindHost,
        int tcpPort,
        int maxConnections,
        int maxPlayersPerRoom,
        int outboundQueueLimit,
        int inboundFramesPerSecond,
        int workerThreads,
        Duration idleTimeout,
        String devRoomCode,
        boolean logIo
) {
    public static final int DEFAULT_TCP_PORT = 5000;

    public ServerConfig {
        if (bindHost == null || bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost is required");
        }
        if (tcpPort < 0 || tcpPort > 65_535) {
            throw new IllegalArgumentException("tcpPort must be between 0 and 65535");
        }
        requirePositive(maxConnections, "maxConnections");
        requirePositive(maxPlayersPerRoom, "maxPlayersPerRoom");
        requirePositive(outboundQueueLimit, "outboundQueueLimit");
        requirePositive(inboundFramesPerSecond, "inboundFramesPerSecond");
        requirePositive(workerThreads, "workerThreads");
        if (idleTimeout == null || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout cannot be negative");
        }
    }

    public static ServerConfig defaults() {
        return new ServerConfig("0.0.0.0", DEFAULT_TCP_PORT, 256, 32, 256, 200, 4,
                Duration.ofMinutes(30), null, true);
    }

    /** Reads the settings above from the given environment map (normally {@code System.getenv()}). */
    public static ServerConfig fromEnv(Map<String, String> env) {
        ServerConfig base = defaults();
        return new ServerConfig(
                text(env, "QUIZ_BIND_HOST", base.bindHost()),
                number(env, "QUIZ_TCP_PORT", base.tcpPort()),
                number(env, "QUIZ_MAX_CONNECTIONS", base.maxConnections()),
                number(env, "QUIZ_MAX_PLAYERS", base.maxPlayersPerRoom()),
                base.outboundQueueLimit(),
                base.inboundFramesPerSecond(),
                base.workerThreads(),
                Duration.ofSeconds(number(env, "QUIZ_IDLE_TIMEOUT_SECONDS", (int) base.idleTimeout().toSeconds())),
                blankToNull(env.get("QUIZ_DEV_ROOM")),
                !"false".equalsIgnoreCase(env.getOrDefault("QUIZ_LOG_IO", "true").strip()));
    }

    public ServerConfig withTcpPort(int value) {
        return new ServerConfig(bindHost, value, maxConnections, maxPlayersPerRoom, outboundQueueLimit,
                inboundFramesPerSecond, workerThreads, idleTimeout, devRoomCode, logIo);
    }

    public ServerConfig withBindHost(String value) {
        return new ServerConfig(value, tcpPort, maxConnections, maxPlayersPerRoom, outboundQueueLimit,
                inboundFramesPerSecond, workerThreads, idleTimeout, devRoomCode, logIo);
    }

    public ServerConfig withDevRoomCode(String value) {
        return new ServerConfig(bindHost, tcpPort, maxConnections, maxPlayersPerRoom, outboundQueueLimit,
                inboundFramesPerSecond, workerThreads, idleTimeout, value, logIo);
    }

    public ServerConfig withMaxConnections(int value) {
        return new ServerConfig(bindHost, tcpPort, value, maxPlayersPerRoom, outboundQueueLimit,
                inboundFramesPerSecond, workerThreads, idleTimeout, devRoomCode, logIo);
    }

    public ServerConfig withMaxPlayersPerRoom(int value) {
        return new ServerConfig(bindHost, tcpPort, maxConnections, value, outboundQueueLimit,
                inboundFramesPerSecond, workerThreads, idleTimeout, devRoomCode, logIo);
    }

    public ServerConfig withOutboundQueueLimit(int value) {
        return new ServerConfig(bindHost, tcpPort, maxConnections, maxPlayersPerRoom, value,
                inboundFramesPerSecond, workerThreads, idleTimeout, devRoomCode, logIo);
    }

    public ServerConfig withInboundFramesPerSecond(int value) {
        return new ServerConfig(bindHost, tcpPort, maxConnections, maxPlayersPerRoom, outboundQueueLimit,
                value, workerThreads, idleTimeout, devRoomCode, logIo);
    }

    public ServerConfig withIdleTimeout(Duration value) {
        return new ServerConfig(bindHost, tcpPort, maxConnections, maxPlayersPerRoom, outboundQueueLimit,
                inboundFramesPerSecond, workerThreads, value, devRoomCode, logIo);
    }

    public ServerConfig withLogIo(boolean value) {
        return new ServerConfig(bindHost, tcpPort, maxConnections, maxPlayersPerRoom, outboundQueueLimit,
                inboundFramesPerSecond, workerThreads, idleTimeout, devRoomCode, value);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String text(Map<String, String> env, String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int number(Map<String, String> env, String name, int fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be a whole number, got: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
