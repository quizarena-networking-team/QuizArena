package com.quizarena.security;

import java.util.Arrays;

/** Password-verification data loaded from the hosts table; never sent over the network. */
public record HostAccount(long id, String username, byte[] passwordSalt,
                          byte[] passwordHash, int passwordIterations) {
    public HostAccount {
        if (id <= 0 || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Host identity is required");
        }
        if (passwordSalt == null || passwordSalt.length < 16) {
            throw new IllegalArgumentException("Password salt must contain at least 16 bytes");
        }
        if (passwordHash == null || passwordHash.length < 32) {
            throw new IllegalArgumentException("Password hash must contain at least 32 bytes");
        }
        if (passwordIterations < PasswordHasher.MINIMUM_ITERATIONS) {
            throw new IllegalArgumentException("Password iteration count is too low");
        }
        passwordSalt = Arrays.copyOf(passwordSalt, passwordSalt.length);
        passwordHash = Arrays.copyOf(passwordHash, passwordHash.length);
    }

    @Override public byte[] passwordSalt() {
        return Arrays.copyOf(passwordSalt, passwordSalt.length);
    }

    @Override public byte[] passwordHash() {
        return Arrays.copyOf(passwordHash, passwordHash.length);
    }
}
