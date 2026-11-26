package com.quizarena.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/** PBKDF2-HMAC-SHA-256 password hashing using a unique random salt per host. */
public final class PasswordHasher {
    public static final int MINIMUM_ITERATIONS = 100_000;
    public static final int DEFAULT_ITERATIONS = 210_000;
    public static final int SALT_BYTES = 16;
    public static final int HASH_BYTES = 32;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private final SecureRandom random;

    public PasswordHasher() {
        this(new SecureRandom());
    }

    PasswordHasher(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public PasswordRecord hash(char[] password) {
        validatePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new PasswordRecord(salt, derive(password, salt, DEFAULT_ITERATIONS, HASH_BYTES),
                DEFAULT_ITERATIONS);
    }

    public boolean verify(char[] password, HostAccount account) {
        Objects.requireNonNull(account, "account");
        if (password == null) {
            return false;
        }
        byte[] expected = account.passwordHash();
        byte[] actual = derive(password, account.passwordSalt(), account.passwordIterations(), expected.length);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int lengthBytes) {
        var specification = new PBEKeySpec(password, salt, iterations, Math.multiplyExact(lengthBytes, Byte.SIZE));
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", failure);
        } finally {
            specification.clearPassword();
        }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 256) {
            throw new IllegalArgumentException("Password must contain between 12 and 256 characters");
        }
    }

    /** Values persisted in hosts.password_salt/hash/iterations. */
    public record PasswordRecord(byte[] salt, byte[] hash, int iterations) {
        public PasswordRecord {
            salt = Arrays.copyOf(salt, salt.length);
            hash = Arrays.copyOf(hash, hash.length);
        }

        @Override public byte[] salt() { return Arrays.copyOf(salt, salt.length); }
        @Override public byte[] hash() { return Arrays.copyOf(hash, hash.length); }
    }
}

