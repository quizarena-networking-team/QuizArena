package com.quizarena.security;

import java.sql.SQLException;
import java.util.Optional;

@FunctionalInterface
public interface HostRepository {
    Optional<HostAccount> findByUsername(String username) throws SQLException;
}

