package com.quizarena.security.tls;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded per-client and username failed-login throttling for the shared TLS listener. */
final class LoginAttemptLimiter {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final Duration BLOCK = Duration.ofSeconds(30);
    private static final int MAX_KEYS = 10_000;

    private record Key(String remote, String username) { }
    private static final class Attempts {
        long windowStarted;
        int failures;
        long blockedUntil;
    }

    private final Clock clock;
    private final Map<Key, Attempts> attempts = new HashMap<>();

    LoginAttemptLimiter() { this(Clock.systemUTC()); }
    LoginAttemptLimiter(Clock clock) { this.clock = clock; }

    synchronized boolean allow(TlsSessionContext session, String username) {
        long now = clock.millis();
        Attempts value = attempts.get(key(session, username));
        if (value == null) return true;
        if (value.blockedUntil > now) return false;
        if (now - value.windowStarted >= WINDOW.toMillis()) attempts.remove(key(session, username));
        return true;
    }

    synchronized void failed(TlsSessionContext session, String username) {
        long now = clock.millis();
        Key key = key(session, username);
        if (!attempts.containsKey(key) && attempts.size() >= MAX_KEYS) {
            attempts.entrySet().stream()
                    .min((left, right) -> Long.compare(left.getValue().windowStarted,
                            right.getValue().windowStarted))
                    .ifPresent(oldest -> attempts.remove(oldest.getKey()));
        }
        Attempts value = attempts.computeIfAbsent(key, ignored -> new Attempts());
        if (value.windowStarted == 0 || now - value.windowStarted >= WINDOW.toMillis()) {
            value.windowStarted = now;
            value.failures = 0;
        }
        if (++value.failures >= MAX_FAILURES) value.blockedUntil = now + BLOCK.toMillis();
    }

    synchronized void succeeded(TlsSessionContext session, String username) {
        attempts.remove(key(session, username));
    }

    private static Key key(TlsSessionContext session, String username) {
        String remote = session.remoteAddress() instanceof InetSocketAddress address
                ? address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress()
                : String.valueOf(session.remoteAddress());
        return new Key(remote, username.strip().toLowerCase(Locale.ROOT));
    }
}
