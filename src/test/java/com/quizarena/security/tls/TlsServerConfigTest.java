package com.quizarena.security.tls;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TlsServerConfigTest {
    @Test
    void loadsDefaultsAndRedactsPasswords() {
        var config = TlsServerConfig.fromEnv(required());
        assertEquals("0.0.0.0", config.bindHost());
        assertEquals(8443, config.port());
        assertEquals(Path.of("server.p12").toAbsolutePath().normalize(), config.keyStorePath());
        assertEquals(32, config.maxConnections());
        assertEquals(Duration.ofSeconds(60), config.socketTimeout());
        assertArrayEquals("store-secret".toCharArray(), config.keyPassword());
        assertFalse(config.toString().contains("store-secret"));
        assertTrue(config.toString().contains("<redacted>"));
    }

    @Test
    void loadsOverridesAndSeparateKeyPassword() {
        var env = required();
        env.put("QUIZ_TLS_BIND_HOST", "127.0.0.1");
        env.put("QUIZ_TLS_PORT", "0");
        env.put("QUIZ_TLS_KEY_PASSWORD", "key-secret");
        env.put("QUIZ_TLS_MAX_CONNECTIONS", "5");
        env.put("QUIZ_TLS_SOCKET_TIMEOUT_SECONDS", "12");
        var config = TlsServerConfig.fromEnv(env);
        assertEquals("127.0.0.1", config.bindHost());
        assertEquals(0, config.port());
        assertArrayEquals("key-secret".toCharArray(), config.keyPassword());
        assertEquals(5, config.maxConnections());
        assertEquals(Duration.ofSeconds(12), config.socketTimeout());
    }

    @Test
    void returnedPasswordArraysCannotMutateConfiguration() {
        var config = TlsServerConfig.fromEnv(required());
        char[] password = config.keyStorePassword();
        password[0] = 'X';
        assertEquals('s', config.keyStorePassword()[0]);
    }

    @Test
    void rejectsMissingSecretsAndInvalidNumbers() {
        for (String missing : new String[] { "QUIZ_TLS_KEYSTORE_PATH", "QUIZ_TLS_KEYSTORE_PASSWORD" }) {
            var env = required(); env.remove(missing);
            assertThrows(IllegalArgumentException.class, () -> TlsServerConfig.fromEnv(env));
        }
        for (String value : new String[] { "-1", "65536", "text" }) {
            var env = required(); env.put("QUIZ_TLS_PORT", value);
            assertThrows(IllegalArgumentException.class, () -> TlsServerConfig.fromEnv(env));
        }
    }

    private static Map<String, String> required() {
        return new HashMap<>(Map.of(
                "QUIZ_TLS_KEYSTORE_PATH", "server.p12",
                "QUIZ_TLS_KEYSTORE_PASSWORD", "store-secret"));
    }
}

