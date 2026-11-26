package com.quizarena.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashesAndVerifiesWithoutStoringPlaintext() {
        char[] password = "Correct-Horse-4827!".toCharArray();
        var record = hasher.hash(password);
        var account = new HostAccount(1, "host", record.salt(), record.hash(), record.iterations());

        assertTrue(hasher.verify(password, account));
        assertFalse(hasher.verify("Wrong-Password-4827!".toCharArray(), account));
        assertEquals(PasswordHasher.SALT_BYTES, record.salt().length);
        assertEquals(PasswordHasher.HASH_BYTES, record.hash().length);
        assertEquals(PasswordHasher.DEFAULT_ITERATIONS, record.iterations());
    }

    @Test
    void identicalPasswordsReceiveDifferentSaltsAndHashes() {
        char[] password = "Correct-Horse-4827!".toCharArray();
        var first = hasher.hash(password);
        var second = hasher.hash(password);

        assertFalse(java.util.Arrays.equals(first.salt(), second.salt()));
        assertFalse(java.util.Arrays.equals(first.hash(), second.hash()));
    }

    @Test
    void returnedArraysCannotMutateStoredCredentials() {
        var record = hasher.hash("Correct-Horse-4827!".toCharArray());
        byte[] salt = record.salt();
        byte[] hash = record.hash();
        salt[0] ^= 1;
        hash[0] ^= 1;

        assertNotEquals(salt[0], record.salt()[0]);
        assertNotEquals(hash[0], record.hash()[0]);
    }

    @Test
    void rejectsUnsafePasswordLengths() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("too-short".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(new char[257]));
    }
}

