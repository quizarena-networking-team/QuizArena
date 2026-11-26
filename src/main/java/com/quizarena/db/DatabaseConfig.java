package com.quizarena.db;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validated MySQL connection settings loaded from the server process environment.
 * The password is deliberately redacted from {@link #toString()}.
 */
public record DatabaseConfig(
        String url,
        String user,
        String password,
        Duration connectTimeout,
        Duration socketTimeout
) {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(10);

    public DatabaseConfig {
        url = required(url, "url");
        user = required(user, "user");
        password = required(password, "password");
        connectTimeout = validTimeout(connectTimeout, "connectTimeout", 60);
        socketTimeout = validTimeout(socketTimeout, "socketTimeout", 300);

        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (!lowerUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("url must use jdbc:mysql://");
        }
        if (url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0 || url.length() > 2048) {
            throw new IllegalArgumentException("url is invalid");
        }
        if (lowerUrl.matches(".*[?&](user|password)=.*") || authority(url).contains("@")) {
            throw new IllegalArgumentException("Database credentials must not be embedded in QUIZ_DB_URL");
        }
        if (user.length() > 64 || user.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("user is invalid");
        }
    }

    /** Loads required credentials and optional timeout settings from the supplied environment. */
    public static DatabaseConfig fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new DatabaseConfig(
                env.get("QUIZ_DB_URL"),
                env.get("QUIZ_DB_USER"),
                env.get("QUIZ_DB_PASSWORD"),
                seconds(env, "QUIZ_DB_CONNECT_TIMEOUT_SECONDS", DEFAULT_CONNECT_TIMEOUT, 60),
                seconds(env, "QUIZ_DB_SOCKET_TIMEOUT_SECONDS", DEFAULT_SOCKET_TIMEOUT, 300));
    }

    @Override
    public String toString() {
        return "DatabaseConfig[url=" + url + ", user=" + user + ", password=<redacted>"
                + ", connectTimeout=" + connectTimeout + ", socketTimeout=" + socketTimeout + "]";
    }

    private static Duration seconds(Map<String, String> env, String name, Duration fallback, int maximum) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int seconds = Integer.parseInt(value.strip());
            if (seconds < 1 || seconds > maximum) {
                throw new IllegalArgumentException(name + " must be between 1 and " + maximum + " seconds");
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be a whole number", failure);
        }
    }

    private static Duration validTimeout(Duration value, String name, int maximumSeconds) {
        if (value == null || value.isZero() || value.isNegative()
                || value.toMillis() > Duration.ofSeconds(maximumSeconds).toMillis()) {
            throw new IllegalArgumentException(name + " must be between 1 ms and " + maximumSeconds + " seconds");
        }
        return value;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    private static String authority(String jdbcUrl) {
        int start = "jdbc:mysql://".length();
        int slash = jdbcUrl.indexOf('/', start);
        return slash < 0 ? jdbcUrl.substring(start) : jdbcUrl.substring(start, slash);
    }
}

