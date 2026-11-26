package com.quizarena.security;

import com.quizarena.db.DataSourceFactory;
import com.quizarena.db.DatabaseConfig;

import java.io.Console;
import java.sql.SQLException;
import java.util.Arrays;

/** Local administrator tool for creating a host without exposing its plaintext password. */
public final class HostAccountProvisioner {
    private HostAccountProvisioner() {
    }

    public static void main(String[] args) throws SQLException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: HostAccountProvisioner <username>");
        }
        String username = args[0].strip();
        if (username.length() < 3 || username.length() > 64
                || username.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Username must contain 3-64 characters and no spaces");
        }

        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Run this tool in a real terminal so the password is hidden");
        }
        char[] first = console.readPassword("New QuizArena host password: ");
        char[] second = console.readPassword("Confirm password: ");
        try {
            if (!Arrays.equals(first, second)) {
                throw new IllegalArgumentException("Passwords do not match");
            }
            var config = DatabaseConfig.fromEnv(System.getenv());
            var repository = new JdbcHostRepository(DataSourceFactory.create(config));
            long id = repository.create(username, new PasswordHasher().hash(first));
            console.printf("Created host '%s' with ID %d.%n", username, id);
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }
}
