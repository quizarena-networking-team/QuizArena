package com.quizarena.security;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HostAuthenticatorTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void authenticatesAgainstStoredHashAndReturnsOnlyPublicIdentity() throws Exception {
        char[] password = "Correct-Horse-4827!".toCharArray();
        HostAccount account = account(password);
        var requested = new AtomicReference<String>();
        HostRepository repository = username -> {
            requested.set(username);
            return Optional.of(account);
        };

        var result = new HostAuthenticator(repository, hasher).authenticate("  host-owner  ", password);

        assertEquals("host-owner", requested.get());
        assertEquals(Optional.of(new AuthenticatedHost(7, "host-owner")), result);
    }

    @Test
    void wrongPasswordAndUnknownUsernameHaveTheSameEmptyResult() throws Exception {
        HostAccount account = account("Correct-Horse-4827!".toCharArray());
        var authenticator = new HostAuthenticator(
                username -> username.equals("host-owner") ? Optional.of(account) : Optional.empty(), hasher);

        assertEquals(Optional.empty(), authenticator.authenticate(
                "host-owner", "Wrong-Password-4827!".toCharArray()));
        assertEquals(Optional.empty(), authenticator.authenticate(
                "missing-host", "Wrong-Password-4827!".toCharArray()));
        assertEquals(Optional.empty(), authenticator.authenticate(null, null));
    }

    @Test
    void databaseFailureIsNotMisreportedAsBadCredentials() {
        HostRepository unavailable = username -> { throw new SQLException("offline"); };
        var authenticator = new HostAuthenticator(unavailable, hasher);

        assertThrows(SQLException.class, () -> authenticator.authenticate(
                "host-owner", "Correct-Horse-4827!".toCharArray()));
    }

    private HostAccount account(char[] password) {
        var record = hasher.hash(password);
        return new HostAccount(7, "host-owner", record.salt(), record.hash(), record.iterations());
    }
}
