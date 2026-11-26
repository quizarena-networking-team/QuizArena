package com.quizarena.security.tls;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Validated TLS listener and private-key settings loaded from environment variables. */
public record TlsServerConfig(
        String bindHost,
        int port,
        Path keyStorePath,
        char[] keyStorePassword,
        char[] keyPassword,
        int maxConnections,
        Duration socketTimeout
) {
    public static final int DEFAULT_PORT = 8443;

    public TlsServerConfig {
        if (bindHost == null || bindHost.isBlank()) throw new IllegalArgumentException("bindHost is required");
        bindHost = bindHost.strip();
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port must be between 0 and 65535");
        keyStorePath = Objects.requireNonNull(keyStorePath, "keyStorePath").toAbsolutePath().normalize();
        keyStorePassword = copyRequired(keyStorePassword, "keyStorePassword");
        keyPassword = copyRequired(keyPassword, "keyPassword");
        if (maxConnections < 1 || maxConnections > 10_000) {
            throw new IllegalArgumentException("maxConnections must be between 1 and 10000");
        }
        if (socketTimeout == null || socketTimeout.isNegative() || socketTimeout.isZero()
                || socketTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("socketTimeout must be between 1 ms and 10 minutes");
        }
    }

    public static TlsServerConfig fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        char[] storePassword = required(env, "QUIZ_TLS_KEYSTORE_PASSWORD").toCharArray();
        String key = env.get("QUIZ_TLS_KEY_PASSWORD");
        char[] keyPassword = key == null || key.isBlank()
                ? Arrays.copyOf(storePassword, storePassword.length)
                : key.toCharArray();
        return new TlsServerConfig(
                text(env, "QUIZ_TLS_BIND_HOST", "0.0.0.0"),
                number(env, "QUIZ_TLS_PORT", DEFAULT_PORT),
                Path.of(required(env, "QUIZ_TLS_KEYSTORE_PATH")),
                storePassword,
                keyPassword,
                number(env, "QUIZ_TLS_MAX_CONNECTIONS", 32),
                Duration.ofSeconds(number(env, "QUIZ_TLS_SOCKET_TIMEOUT_SECONDS", 60)));
    }

    @Override public char[] keyStorePassword() {
        return Arrays.copyOf(keyStorePassword, keyStorePassword.length);
    }

    @Override public char[] keyPassword() {
        return Arrays.copyOf(keyPassword, keyPassword.length);
    }

    @Override public String toString() {
        return "TlsServerConfig[bindHost=" + bindHost + ", port=" + port
                + ", keyStorePath=" + keyStorePath + ", keyStorePassword=<redacted>"
                + ", keyPassword=<redacted>, maxConnections=" + maxConnections
                + ", socketTimeout=" + socketTimeout + "]";
    }

    private static char[] copyRequired(char[] value, String name) {
        if (value == null || value.length == 0) throw new IllegalArgumentException(name + " is required");
        return Arrays.copyOf(value, value.length);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }

    private static String text(Map<String, String> env, String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int number(Map<String, String> env, String name, int fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be a whole number", failure);
        }
    }
}

