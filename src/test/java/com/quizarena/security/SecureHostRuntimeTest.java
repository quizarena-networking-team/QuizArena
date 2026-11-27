package com.quizarena.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecureHostRuntimeTest {
    @Test
    void secureHostRuntimeIsOptIn() {
        assertFalse(SecureHostRuntime.isEnabled(Map.of()));
        assertTrue(SecureHostRuntime.isEnabled(Map.of("QUIZ_TLS_ENABLED", "yes")));
        assertFalse(SecureHostRuntime.isEnabled(Map.of("QUIZ_TLS_ENABLED", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> SecureHostRuntime.isEnabled(Map.of("QUIZ_TLS_ENABLED", "sometimes")));
    }
}
