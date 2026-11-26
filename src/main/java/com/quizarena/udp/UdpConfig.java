package com.quizarena.udp;

import java.time.Duration;
import java.util.Map;

/**
 * UDP and multicast settings shared by the server and the player client.
 * Environment variable names follow the architecture blueprint (Appendix C).
 *
 * <pre>
 * QUIZ_DISCOVERY_PORT        default 5001
 * QUIZ_HEARTBEAT_PORT        default 5002
 * QUIZ_MULTICAST_GROUP       default 239.10.10.10
 * QUIZ_MULTICAST_PORT        default 5003
 * QUIZ_MULTICAST_INTERFACE   optional network interface name (e.g. "wlan0"); auto-selected if unset
 * QUIZ_TLS_PORT              default 8443 (advertised in DISCOVER_RESULT only)
 * </pre>
 */
public record UdpConfig(
        int discoveryPort,
        int heartbeatPort,
        String multicastGroup,
        int multicastPort,
        String multicastInterface,
        int tlsPort,
        Duration heartbeatInterval,
        Duration heartbeatStaleAfter
) {
    public UdpConfig {
        requirePort(discoveryPort, "discoveryPort");
        requirePort(heartbeatPort, "heartbeatPort");
        requirePort(multicastPort, "multicastPort");
        requirePort(tlsPort, "tlsPort");
        if (multicastGroup == null || multicastGroup.isBlank()) {
            throw new IllegalArgumentException("multicastGroup is required");
        }
        if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        if (heartbeatStaleAfter == null || heartbeatStaleAfter.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("heartbeatStaleAfter must be longer than heartbeatInterval");
        }
    }

    public static UdpConfig defaults() {
        return new UdpConfig(5001, 5002, "239.10.10.10", 5003, null, 8443,
                Duration.ofSeconds(2), Duration.ofSeconds(6));
    }

    /** Reads the settings above from the given environment map (normally {@code System.getenv()}). */
    public static UdpConfig fromEnv(Map<String, String> env) {
        UdpConfig base = defaults();
        return new UdpConfig(
                number(env, "QUIZ_DISCOVERY_PORT", base.discoveryPort()),
                number(env, "QUIZ_HEARTBEAT_PORT", base.heartbeatPort()),
                text(env, "QUIZ_MULTICAST_GROUP", base.multicastGroup()),
                number(env, "QUIZ_MULTICAST_PORT", base.multicastPort()),
                text(env, "QUIZ_MULTICAST_INTERFACE", null),
                number(env, "QUIZ_TLS_PORT", base.tlsPort()),
                base.heartbeatInterval(),
                base.heartbeatStaleAfter());
    }

    private static void requirePort(int port, String name) {
        // 0 is allowed so tests can bind ephemeral ports.
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535");
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
}
