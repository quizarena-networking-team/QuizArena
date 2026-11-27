package com.quizarena.integration;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.db.DataSourceFactory;
import com.quizarena.db.DatabaseConfig;
import com.quizarena.metrics.NetworkEvent;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.JdbcHostRepository;
import com.quizarena.security.PasswordHasher;
import com.quizarena.security.SecureHostRuntime;
import com.quizarena.security.tls.TlsFrameReader;
import com.quizarena.server.ServerConfig;
import com.quizarena.server.ServerMain;
import com.quizarena.udp.LanServices;
import com.quizarena.udp.UdpConfig;
import com.quizarena.udp.discovery.DiscoveryClient;
import com.quizarena.udp.presence.HeartbeatSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import static com.quizarena.protocol.MessageTypes.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUIZ_E2E_DB_URL", matches = ".+")
@Timeout(120)
class FullNetworkingIntegrationTest {
    private static final String LOOPBACK = "127.0.0.1";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String STANDARD_QUESTION = "What is two plus two?";
    private static final String BUZZER_QUESTION = "What is five plus five?";
    private static final Map<String, String> QUESTION_OPTIONS = Map.of("A", "4", "B", "6", "C", "8", "D", "10");
    private static final List<String> PLAYERS = List.of("Alice", "Bob");

    @TempDir
    Path tempDir;

    @Test
    void hostsPollAndQuizAcrossTlsAndNioAndCommitsResultsToMySql() throws Exception {
        Map<String, String> settings = databaseSettings();
        DataSource source = DataSourceFactory.create(DatabaseConfig.fromEnv(settings));
        String storePassword = UUID.randomUUID().toString();
        Path keyStore = tempDir.resolve("loopback.p12");
        SSLContext hostTls = createClientTlsContext(keyStore, storePassword);
        settings.putAll(Map.of("QUIZ_TLS_ENABLED", "true", "QUIZ_TLS_BIND_HOST", LOOPBACK,
                "QUIZ_TLS_PORT", "0", "QUIZ_TLS_KEYSTORE_PATH", keyStore.toString(),
                "QUIZ_TLS_KEYSTORE_PASSWORD", storePassword, "QUIZ_TLS_SOCKET_TIMEOUT_SECONDS", "30"));
        var config = ServerConfig.defaults().withBindHost(LOOPBACK).withTcpPort(0);
        var events = new LinkedBlockingQueue<ProtocolMessage>();

        try (var fixture = new Fixture(source)) {
            fixture.seed();
            var backend = ServerMain.assemble(config);
            String room;
            try (backend) {
                backend.server().start();
                try (var runtime = SecureHostRuntime.start(settings, backend.rooms(), backend.dispatcher());
                     var lan = startLan(backend, runtime);
                     var host = (SSLSocket) hostTls.getSocketFactory().createSocket();
                     var alice = new TcpQuizClient(REQUEST_TIMEOUT, REQUEST_TIMEOUT);
                     var bob = new TcpQuizClient(REQUEST_TIMEOUT, REQUEST_TIMEOUT);
                     var subscription = alice.subscribe(events::add)) {
                    assertDiscovery(backend, runtime, lan);
                    connectHost(host, runtime.boundPort());
                    var login = hostRequest(host, HOST_LOGIN, HOST_LOGIN_RESULT, null,
                            Map.of("username", fixture.username, "password", fixture.password));
                    assertEquals(Map.of("hostId", Long.toString(fixture.hostId), "username", fixture.username),
                            login.payload());
                    var created = hostRequest(host, CREATE_ROOM, CREATE_ROOM_RESULT, null, Map.of());
                    room = (String) created.payload().get("roomCode");
                    assertNotNull(room, "CREATE_ROOM did not return a room code");
                    assertEquals(room, created.roomCode());
                    long sessionId = Long.parseLong((String) created.payload().get("sessionId"));

                    String aliceId = join(alice, backend.server().boundPort(), room, PLAYERS.getFirst());
                    assertEquals(List.of(player(aliceId, PLAYERS.getFirst())),
                            expectEvent(events, room, LOBBY_UPDATED).payload().get("players"));
                    String bobId = join(bob, backend.server().boundPort(), room, PLAYERS.getLast());
                    assertNotEquals(aliceId, bobId);
                    assertEquals(Set.of(player(aliceId, PLAYERS.getFirst()), player(bobId, PLAYERS.getLast())),
                            Set.copyOf((List<?>) expectEvent(events, room, LOBBY_UPDATED).payload().get("players")));
                    assertHeartbeat(backend, lan, room, aliceId);

                    runPoll(host, alice, bob, events, room);
                    var standings = runQuiz(host, alice, bob, events, room, fixture, aliceId, bobId);
                    fixture.verifyCommitted(sessionId, room, standings);

                    playerRequest(bob, LEAVE_ROOM, LEAVE_ROOM_RESULT, room, Map.of(), null);
                    bob.disconnect();
                    assertFalse(bob.isConnected());
                    assertEquals(List.of(player(aliceId, PLAYERS.getFirst())),
                            expectEvent(events, room, LOBBY_UPDATED).payload().get("players"));
                    playerRequest(alice, LEAVE_ROOM, LEAVE_ROOM_RESULT, room, Map.of(), null);
                    alice.disconnect();
                    assertFalse(alice.isConnected());
                }
                assertFalse(backend.rooms().isOpen(room), "Secure runtime must close its room on shutdown");
            }
            assertEquals(0, backend.server().connectionCount());
            assertEquals(1L, backend.events().count(NetworkEvent.Kind.SERVER_STOPPED));
        }
    }

    private static void runPoll(SSLSocket host, TcpQuizClient alice, TcpQuizClient bob,
                                LinkedBlockingQueue<ProtocolMessage> events, String room) throws Exception {
        String question = "Which label do you prefer?";
        var options = Map.of("A", "Alpha", "B", "Beta");
        var started = hostRequest(host, START_POLL, START_POLL_RESULT, room,
                Map.of("question", question, "options", options));
        String pollId = (String) started.payload().get("pollId");
        assertNotNull(pollId);
        assertEquals(Map.of("pollId", pollId, "question", question, "options", options, "counts", Map.of("A", 0, "B", 0)),
                expectEvent(events, room, POLL_STARTED).payload());
        assertVote(alice, room, pollId, "A");
        assertPollCounts(expectEvent(events, room, POLL_RESULTS), pollId, 1, 0);
        playerRequest(alice, SUBMIT_VOTE, SUBMIT_VOTE_RESULT, room,
                Map.of("pollId", pollId, "optionId", "B"), "ALREADY_VOTED");
        assertVote(bob, room, pollId, "B");
        assertPollCounts(expectEvent(events, room, POLL_RESULTS), pollId, 1, 1);
        assertPollCounts(hostRequest(host, CLOSE_POLL, CLOSE_POLL_RESULT, room, Map.of("pollId", pollId)),
                pollId, 1, 1);
        assertPollCounts(expectEvent(events, room, POLL_CLOSED), pollId, 1, 1);
    }

    private static void assertVote(TcpQuizClient client, String room, String pollId, String option) throws Exception {
        var vote = playerRequest(client, SUBMIT_VOTE, SUBMIT_VOTE_RESULT, room,
                Map.of("pollId", pollId, "optionId", option), null);
        assertEquals(Map.of("pollId", pollId, "optionId", option, "accepted", true), vote.payload());
    }

    private static void assertPollCounts(ProtocolMessage message, String pollId, int first, int second) {
        assertEquals(pollId, message.payload().get("pollId"), message.type());
        assertEquals(Map.of("A", first, "B", second), message.payload().get("counts"), message.type());
    }

    private static List<Standing> runQuiz(SSLSocket host, TcpQuizClient alice, TcpQuizClient bob,
                                          LinkedBlockingQueue<ProtocolMessage> events, String room,
                                          Fixture fixture, String aliceId, String bobId) throws Exception {
        String quizId = Long.toString(fixture.quizId);
        String buzzerId = Long.toString(fixture.buzzerQuestionId);
        var started = hostRequest(host, START_QUIZ, START_QUIZ_RESULT, room,
                Map.of("quizId", quizId, "buzzerQuestionId", buzzerId));
        assertEquals(quizId, started.payload().get("quizId"));
        var standard = question(expectEvent(events, room, QUESTION_STARTED), Long.toString(fixture.standardQuestionId),
                STANDARD_QUESTION, "STANDARD");
        assertAnswer(alice, room, standard, "A");
        assertAnswer(bob, room, standard, "B");
        assertQuestionEnd(expectEvent(events, room, QUESTION_ENDED), standard, "A", "TIMEOUT");
        var standardBoard = List.of(new Standing(aliceId, PLAYERS.getFirst(), 100, 1, 1, 1),
                new Standing(bobId, PLAYERS.getLast(), 0, 0, 1, 2));
        assertLeaderboard(expectEvent(events, room, LEADERBOARD_UPDATED), standardBoard);

        hostRequest(host, OPEN_BUZZER, OPEN_BUZZER_RESULT, room,
                Map.of("buzzDurationSeconds", 10, "answerDurationSeconds", 10));
        var buzzer = question(expectEvent(events, room, BUZZER_OPENED), buzzerId, BUZZER_QUESTION, "BUZZER");
        assertNotEquals(standard.get("roundId"), buzzer.get("roundId"));
        var press = playerRequest(alice, PRESS_BUZZER, PRESS_BUZZER_RESULT, room, buzzer, null);
        assertTrue(Boolean.TRUE.equals(press.payload().get("winner")));
        buzzer.forEach((key, value) -> assertEquals(value, press.payload().get(key), key));
        var winner = expectEvent(events, room, BUZZER_WINNER);
        buzzer.forEach((key, value) -> assertEquals(value, winner.payload().get(key), key));
        assertEquals(aliceId, winner.payload().get("playerId"));
        playerRequest(bob, PRESS_BUZZER, PRESS_BUZZER_RESULT, room, buzzer, "BUZZER_CLOSED");
        playerRequest(bob, SUBMIT_ANSWER, SUBMIT_ANSWER_RESULT, room, answer(buzzer, "D"), "UNAUTHORIZED");
        assertAnswer(alice, room, buzzer, "D");
        assertQuestionEnd(expectEvent(events, room, QUESTION_ENDED), buzzer, "D", "ANSWERED");

        var finalBoard = List.of(new Standing(aliceId, PLAYERS.getFirst(), 150, 2, 2, 1), standardBoard.getLast());
        assertLeaderboard(expectEvent(events, room, LEADERBOARD_UPDATED), finalBoard);
        assertLeaderboard(playerRequest(alice, GET_LEADERBOARD, LEADERBOARD_RESULT, room, Map.of(), null), finalBoard);
        var finished = hostRequest(host, FINISH_QUIZ, FINISH_QUIZ_RESULT, room, Map.of());
        assertEquals(Boolean.TRUE, finished.payload().get("persisted"));
        var finishedEvent = expectEvent(events, room, QUIZ_FINISHED);
        assertEquals(Boolean.TRUE, finishedEvent.payload().get("persisted"));
        assertEquals(quizId, finishedEvent.payload().get("quizId"));
        assertLeaderboard(finishedEvent, finalBoard);
        return finalBoard;
    }

    private static Map<String, Object> question(ProtocolMessage event, String id, String text, String mode) {
        assertEquals(id, event.payload().get("questionId"));
        assertEquals(text, event.payload().get("text"));
        assertEquals(mode, event.payload().get("mode"));
        assertEquals(QUESTION_OPTIONS, event.payload().get("options"));
        assertInstanceOf(Number.class, event.payload().get("deadlineEpochMs"));
        assertFalse(event.payload().containsKey("correctOptionId"));
        String roundId = assertInstanceOf(String.class, event.payload().get("roundId"));
        assertFalse(roundId.isBlank());
        return Map.of("questionId", id, "roundId", roundId);
    }

    private static Map<String, Object> answer(Map<String, Object> round, String option) {
        return Map.of("questionId", round.get("questionId"), "roundId", round.get("roundId"), "optionId", option);
    }

    private static void assertAnswer(TcpQuizClient client, String room, Map<String, Object> round, String option)
            throws Exception {
        var response = playerRequest(client, SUBMIT_ANSWER, SUBMIT_ANSWER_RESULT, room, answer(round, option), null);
        assertEquals(Map.of("questionId", round.get("questionId"), "roundId", round.get("roundId"), "accepted", true),
                response.payload());
    }

    private static void assertQuestionEnd(ProtocolMessage event, Map<String, Object> round, String correct, String reason) {
        assertEquals(Map.of("questionId", round.get("questionId"), "roundId", round.get("roundId"),
                "correctOptionId", correct, "reason", reason), event.payload());
    }

    private record Standing(String playerId, String name, int score, int correct, int answered, int rank) {
        Map<String, Object> publicEntry() {
            return Map.of("playerId", playerId, "playerName", name, "score", score, "rank", rank);
        }
    }

    private static void assertLeaderboard(ProtocolMessage message, List<Standing> standings) {
        assertEquals(standings.stream().map(Standing::publicEntry).toList(), message.payload().get("entries"),
                message.type());
    }

    private static String join(TcpQuizClient player, int port, String room, String name) throws Exception {
        player.connect(LOOPBACK, port).get(10, SECONDS);
        var joined = playerRequest(player, JOIN_ROOM, JOIN_ROOM_RESULT, room, Map.of("playerName", name), null);
        assertEquals(name, joined.payload().get("playerName"));
        String playerId = assertInstanceOf(String.class, joined.payload().get("playerId"));
        assertEquals(playerId, UUID.fromString(playerId).toString(), "server-assigned player ID");
        return playerId;
    }

    private static Map<String, Object> player(String id, String name) {
        return Map.of("playerId", id, "playerName", name);
    }

    private static ProtocolMessage expectEvent(LinkedBlockingQueue<ProtocolMessage> events, String room, String type)
            throws InterruptedException {
        var event = events.poll(15, SECONDS);
        assertNotNull(event, "Timed out waiting for TCP event " + type);
        assertEquals(type, event.type(), "TCP event order");
        assertEquals(room, event.roomCode(), type);
        assertNull(event.requestId(), type);
        assertNull(event.status(), type);
        return event;
    }

    private static ProtocolMessage playerRequest(TcpQuizClient client, String type, String result,
                                                  String room, Map<String, Object> payload, String error)
            throws Exception {
        var request = request(type, room, payload);
        return assertReply(client.request(request).get(10, SECONDS), request, result, error);
    }

    private static ProtocolMessage hostRequest(SSLSocket socket, String type, String result,
                                                String room, Map<String, Object> payload) throws Exception {
        var request = request(type, room, payload);
        socket.getOutputStream().write(MessageCodec.encodeLine(request));
        socket.getOutputStream().flush();
        byte[] reply = new TlsFrameReader(socket.getInputStream()).nextFrame();
        assertNotNull(reply, "TLS connection closed while waiting for " + result);
        return assertReply(MessageCodec.decodeJson(reply), request, result, null);
    }

    private static ProtocolMessage request(String type, String room, Map<String, Object> payload) {
        return new ProtocolMessage(type, type + "-" + UUID.randomUUID(), room, null, null, payload);
    }

    private static ProtocolMessage assertReply(ProtocolMessage reply, ProtocolMessage request, String type, String error) {
        assertEquals(type, reply.type(), request.type());
        assertEquals(request.requestId(), reply.requestId(), type);
        if (request.roomCode() != null) assertEquals(request.roomCode(), reply.roomCode(), type);
        assertEquals(error, reply.errorCode(), type);
        assertEquals(error == null ? "OK" : "ERROR", reply.status(), type);
        return reply;
    }

    private static LanServices startLan(ServerMain.Services backend, SecureHostRuntime runtime) {
        var udp = UdpConfig.defaults();
        var config = new UdpConfig(0, 0, udp.multicastGroup(), udp.multicastPort(), udp.multicastInterface(),
                runtime.boundPort(), udp.heartbeatInterval(), udp.heartbeatStaleAfter());
        return LanServices.start(config, backend.server().boundPort(), backend.rooms(), backend.events());
    }

    private static void assertDiscovery(ServerMain.Services backend, SecureHostRuntime runtime, LanServices lan)
            throws Exception {
        assertTrue(lan.discoveryPort() > 0, "UDP discovery did not bind");
        var targets = Set.of(InetAddress.getByName(LOOPBACK));
        var found = new DiscoveryClient(lan.discoveryPort(), Duration.ofSeconds(1)).discover(targets);
        assertEquals(List.of(new DiscoveryClient.DiscoveredServer(LOOPBACK, backend.server().boundPort(),
                runtime.boundPort(), LanServices.SERVER_NAME)), found);
    }

    private static void assertHeartbeat(ServerMain.Services backend, LanServices lan, String room, String playerId)
            throws Exception {
        assertTrue(lan.heartbeatPort() > 0, "UDP heartbeat did not bind");
        var received = new CountDownLatch(1);
        try (var subscription = backend.events().subscribe(event -> {
            if (event.kind() == NetworkEvent.Kind.UDP_RECEIVED && ("HEARTBEAT room=" + room).equals(event.detail())) {
                received.countDown();
            }
        }); var sender = HeartbeatSender.start(new InetSocketAddress(LOOPBACK, lan.heartbeatPort()),
                room, playerId, Duration.ofMillis(200))) {
            assertTrue(received.await(5, SECONDS), "Server did not accept the joined player's UDP heartbeat");
        }
    }

    private static SSLContext createClientTlsContext(Path keyStore, String password) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        String passwordVariable = "QUIZ_E2E_KEYTOOL_PASSWORD";
        var builder = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "loopback",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=ip:" + LOOPBACK, "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass:env", passwordVariable, "-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put(passwordVariable, password);
        var process = builder.start();
        try {
            assertTrue(process.waitFor(25, SECONDS), "Timed out generating a temporary TLS keystore");
            assertEquals(0, process.exitValue(), "JDK keytool could not generate the temporary TLS keystore");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }

        var identity = KeyStore.getInstance("PKCS12");
        char[] characters = password.toCharArray();
        try (var file = Files.newInputStream(keyStore)) {
            identity.load(file, characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("loopback", identity.getCertificate("loopback"));
        var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        manager.init(trust);
        var context = SSLContext.getInstance("TLSv1.3");
        context.init(null, manager.getTrustManagers(), null);
        return context;
    }

    private static void connectHost(SSLSocket host, int port) throws Exception {
        host.connect(new InetSocketAddress(LOOPBACK, port), 5_000);
        host.setSoTimeout(10_000);
        var parameters = host.getSSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3"});
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        host.setSSLParameters(parameters);
        host.startHandshake();
        assertEquals("TLSv1.3", host.getSession().getProtocol());
    }

    private static Map<String, String> databaseSettings() {
        String url = System.getenv("QUIZ_E2E_DB_URL");
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]{1,5})?/quizarena_e2e(?:_[a-z0-9_]+)?"),
                "Use a local disposable quizarena_e2e database and a JDBC URL without options or credentials");
        var settings = new HashMap<String, String>();
        settings.put("QUIZ_DB_URL", url);
        settings.put("QUIZ_DB_USER", requiredEnvironment("QUIZ_E2E_DB_USER"));
        settings.put("QUIZ_DB_PASSWORD", requiredEnvironment("QUIZ_E2E_DB_PASSWORD"));
        return settings;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "Set " + name + " for the disposable test database");
        return value;
    }

    private static final class Fixture implements AutoCloseable {
        final DataSource source;
        final String username = "e2e-" + UUID.randomUUID();
        final String password = UUID.randomUUID().toString();
        long hostId;
        long quizId;
        long standardQuestionId;
        long buzzerQuestionId;

        Fixture(DataSource source) {
            this.source = source;
        }

        void seed() throws SQLException {
            try (var connection = source.getConnection()) {
                assertTrue(connection.getCatalog().matches("quizarena_e2e(?:_[a-z0-9_]+)?"),
                        "Connected database must be dedicated to the full networking test");
                char[] characters = password.toCharArray();
                try {
                    hostId = new JdbcHostRepository(source).create(username, new PasswordHasher().hash(characters));
                } finally {
                    Arrays.fill(characters, '\0');
                }
                quizId = insert(connection, "INSERT INTO quizzes (title, category, created_by) VALUES (?, ?, ?)",
                        "QuizArena network fixture", "integration", hostId);
                standardQuestionId = insertQuestion(connection, 1, STANDARD_QUESTION, "A");
                buzzerQuestionId = insertQuestion(connection, 2, BUZZER_QUESTION, "D");
            }
        }

        private long insertQuestion(Connection connection, int order, String text, String correct) throws SQLException {
            return insert(connection, """
                    INSERT INTO questions (quiz_id, question_order, question_text,
                        option_a, option_b, option_c, option_d, correct_option, points, duration_seconds)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 100, 8)
                    """, quizId, order, text, QUESTION_OPTIONS.get("A"), QUESTION_OPTIONS.get("B"),
                    QUESTION_OPTIONS.get("C"), QUESTION_OPTIONS.get("D"), correct);
        }

        void verifyCommitted(long sessionId, String room, List<Standing> standings) throws SQLException {
            // This reader has its own JDBC connection, opened only after FINISH_QUIZ and QUIZ_FINISHED.
            try (var connection = source.getConnection()) {
                try (var query = prepare(connection, """
                        SELECT room_code, host_id, quiz_id, status, started_at, ended_at FROM quiz_sessions WHERE id = ?
                        """, sessionId); var rows = query.executeQuery()) {
                    assertTrue(rows.next(), "CREATE_ROOM session must still exist");
                    assertEquals(room, rows.getString("room_code"));
                    assertEquals(hostId, rows.getLong("host_id"));
                    assertEquals(quizId, rows.getLong("quiz_id"));
                    assertEquals("FINISHED", rows.getString("status"));
                    var start = rows.getTimestamp("started_at");
                    var end = rows.getTimestamp("ended_at");
                    assertNotNull(start);
                    assertNotNull(end);
                    assertFalse(end.before(start));
                    assertFalse(rows.next());
                }
                try (var query = prepare(connection, """
                        SELECT player_key, player_name, score, correct_count, answered_count, final_rank
                        FROM quiz_results WHERE session_id = ? ORDER BY final_rank
                        """, sessionId); var rows = query.executeQuery()) {
                    for (var expected : standings) {
                        assertTrue(rows.next(), "Missing result for " + expected.name());
                        var persisted = new Standing(rows.getString("player_key"), rows.getString("player_name"),
                                rows.getInt("score"), rows.getInt("correct_count"), rows.getInt("answered_count"),
                                rows.getInt("final_rank"));
                        assertEquals(expected, persisted);
                    }
                    assertFalse(rows.next(), "Unexpected extra results");
                }
            }
        }

        @Override public void close() throws SQLException {
            if (hostId == 0) return;
            // Test sessions and quizzes are scoped to the generated host; the schema cascades to results/questions.
            try (var connection = source.getConnection()) {
                for (var sql : List.of("DELETE FROM quiz_sessions WHERE host_id = ?",
                        "DELETE FROM quizzes WHERE created_by = ?", "DELETE FROM hosts WHERE id = ?")) {
                    try (var statement = prepare(connection, sql, hostId)) {
                        statement.executeUpdate();
                    }
                }
            }
        }

        private static PreparedStatement prepare(Connection connection, String sql, long id) throws SQLException {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(10);
            statement.setLong(1, id);
            return statement;
        }

        private static long insert(Connection connection, String sql, Object... values) throws SQLException {
            try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setQueryTimeout(10);
                for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
                assertEquals(1, statement.executeUpdate());
                try (var keys = statement.getGeneratedKeys()) {
                    assertTrue(keys.next(), "Fixture row has no generated ID");
                    return keys.getLong(1);
                }
            }
        }
    }
}
