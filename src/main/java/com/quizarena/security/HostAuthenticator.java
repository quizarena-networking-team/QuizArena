package com.quizarena.security;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Verifies database-backed host credentials without trusting client-provided roles or IDs. */
public final class HostAuthenticator {
    private static final String DUMMY_USERNAME = "unknown-host";

    private final HostRepository hosts;
    private final PasswordHasher passwords;
    private final HostAccount dummyAccount;

    public HostAuthenticator(HostRepository hosts, PasswordHasher passwords) {
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        char[] dummyPassword = "timing-only-password".toCharArray();
        try {
            var record = passwords.hash(dummyPassword);
            this.dummyAccount = new HostAccount(Long.MAX_VALUE, DUMMY_USERNAME,
                    record.salt(), record.hash(), record.iterations());
        } finally {
            Arrays.fill(dummyPassword, '\0');
        }
    }

    /** Returns empty for both an unknown username and an incorrect password. */
    public Optional<AuthenticatedHost> authenticate(String username, char[] password) throws SQLException {
        String normalized = normalizeUsername(username);
        Optional<HostAccount> found = normalized == null
                ? Optional.empty()
                : hosts.findByUsername(normalized);
        HostAccount candidate = found.orElse(dummyAccount);
        boolean verified = passwords.verify(password, candidate);
        if (found.isEmpty() || !verified) {
            return Optional.empty();
        }
        HostAccount account = found.orElseThrow();
        return Optional.of(new AuthenticatedHost(account.id(), account.username()));
    }

    private static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String normalized = username.strip();
        if (normalized.length() < 3 || normalized.length() > 64
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return normalized;
    }
}
