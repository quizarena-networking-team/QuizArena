package com.quizarena.security;

/** Public identity established only after server-side password verification. */
public record AuthenticatedHost(long id, String username) {
    public AuthenticatedHost {
        if (id <= 0 || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authenticated host identity is required");
        }
    }
}
