package com.quizarena.db;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigTest {
    @Test
    void loadsRequiredSettingsAndDefaultTimeouts() {
        var config = DatabaseConfig.fromEnv(required());

        assertEquals("jdbc:mysql://localhost:3306/quizarena", config.url());
        assertEquals("quizarena_app", config.user());
        assertEquals("secret-value", config.password());
        assertEquals(Duration.ofSeconds(5), config.connectTimeout());
        assertEquals(Duration.ofSeconds(10), config.socketTimeout());
        assertFalse(config.toString().contains("secret-value"));
        assertTrue(config.toString().contains("<redacted>"));
    }

    @Test
    void loadsExplicitTimeoutsAndBuildsLazyMysqlDataSource() throws Exception {
        var env = required();
        env.put("QUIZ_DB_CONNECT_TIMEOUT_SECONDS", "7");
        env.put("QUIZ_DB_SOCKET_TIMEOUT_SECONDS", "21");
        var config = DatabaseConfig.fromEnv(env);

        var source = assertInstanceOf(MysqlDataSource.class, DataSourceFactory.create(config));
        assertEquals(config.url(), source.getURL());
        assertEquals(config.user(), source.getUser());
        assertEquals(7000, source.getConnectTimeout());
        assertEquals(21000, source.getSocketTimeout());
    }

    @Test
    void rejectsMissingSettings() {
        for (String missing : required().keySet()) {
            var env = required();
            env.remove(missing);
            assertThrows(IllegalArgumentException.class, () -> DatabaseConfig.fromEnv(env), missing);
        }
    }

    @Test
    void rejectsUnsupportedUrlsAndEmbeddedCredentials() {
        for (String url : new String[] {
                "jdbc:postgresql://localhost/quizarena",
                "jdbc:mysql://user:password@localhost/quizarena",
                "jdbc:mysql://localhost/quizarena?password=secret",
                "jdbc:mysql://localhost/quizarena?useUnicode=true&user=admin",
                "jdbc:mysql://localhost/quizarena\nignored"
        }) {
            var env = required();
            env.put("QUIZ_DB_URL", url);
            assertThrows(IllegalArgumentException.class, () -> DatabaseConfig.fromEnv(env), url);
        }
    }

    @Test
    void rejectsInvalidTimeoutsWithoutEchoingValuesAsSecrets() {
        for (String value : new String[] { "0", "-1", "61", "text" }) {
            var env = required();
            env.put("QUIZ_DB_CONNECT_TIMEOUT_SECONDS", value);
            assertThrows(IllegalArgumentException.class, () -> DatabaseConfig.fromEnv(env), value);
        }
    }

    private static Map<String, String> required() {
        return new HashMap<>(Map.of(
                "QUIZ_DB_URL", "jdbc:mysql://localhost:3306/quizarena",
                "QUIZ_DB_USER", "quizarena_app",
                "QUIZ_DB_PASSWORD", "secret-value"));
    }
}

