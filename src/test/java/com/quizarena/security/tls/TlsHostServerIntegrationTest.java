package com.quizarena.security.tls;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.HostAccount;
import com.quizarena.security.HostAuthenticator;
import com.quizarena.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static com.quizarena.protocol.MessageTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class TlsHostServerIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void negotiatesTls13RejectsPreLoginCommandAndAuthenticatesConnection() throws Exception {
        Path keyStore = createKeyStore();
        char[] password = "test-keystore-password".toCharArray();
        var config = new TlsServerConfig("127.0.0.1", 0, keyStore, password, password,
                4, Duration.ofSeconds(5));

        char[] loginPassword = "Correct-Horse-4827!".toCharArray();
        var hasher = new PasswordHasher();
        var stored = hasher.hash(loginPassword);
        var account = new HostAccount(9, "host-admin", stored.salt(), stored.hash(), stored.iterations());
        var authenticator = new HostAuthenticator(
                username -> username.equals("host-admin") ? Optional.of(account) : Optional.empty(), hasher);
        var commands = new TlsHostCommandHandler(authenticator, (request, session, host) ->
                session.send(new ProtocolMessage(CREATE_ROOM_RESULT, request.requestId(), "ABC123",
                        "OK", null, Map.of("roomCode", "ABC123"))));

        try (var server = new TlsHostServer(config, SslContextFactory.serverContext(config), commands)) {
            server.start();
            assertTrue(server.isRunning());
            assertTrue(server.boundPort() > 0);

            try (SSLSocket client = (SSLSocket) trustingContext().getSocketFactory()
                    .createSocket("127.0.0.1", server.boundPort())) {
                client.setEnabledProtocols(new String[] { "TLSv1.3" });
                client.startHandshake();
                assertEquals("TLSv1.3", client.getSession().getProtocol());
                var input = new TlsFrameReader(new BufferedInputStream(client.getInputStream()));

                send(client, new ProtocolMessage(CREATE_ROOM, "r1", null, null, null, Map.of()));
                assertResponse(input, CREATE_ROOM_RESULT, "r1", "ERROR", "UNAUTHORIZED");

                send(client, new ProtocolMessage(HOST_LOGIN, "r2", null, null, null,
                        Map.of("username", "host-admin", "password", new String(loginPassword))));
                assertResponse(input, HOST_LOGIN_RESULT, "r2", "OK", null);

                send(client, new ProtocolMessage(CREATE_ROOM, "r3", null, null, null, Map.of()));
                ProtocolMessage created = assertResponse(input, CREATE_ROOM_RESULT, "r3", "OK", null);
                assertEquals("ABC123", created.payload().get("roomCode"));
            }
        }
    }

    private Path createKeyStore() throws Exception {
        Path keyStore = temporaryDirectory.resolve("server.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(
                keytool.toString(), "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-keystore", keyStore.toString(),
                "-storepass", "test-keystore-password",
                "-keypass", "test-keystore-password",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-noprompt")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return keyStore;
    }

    private static SSLContext trustingContext() throws Exception {
        // Test-only trust manager for the temporary self-signed loopback certificate.
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, new TrustManager[] { trust }, new SecureRandom());
        return context;
    }

    private static void send(SSLSocket socket, ProtocolMessage message) throws Exception {
        socket.getOutputStream().write(MessageCodec.encodeLine(message));
        socket.getOutputStream().flush();
    }

    private static ProtocolMessage assertResponse(TlsFrameReader input, String type, String requestId,
                                                   String status, String errorCode) throws Exception {
        ProtocolMessage response = MessageCodec.decodeJson(input.nextFrame());
        assertEquals(type, response.type());
        assertEquals(requestId, response.requestId());
        assertEquals(status, response.status());
        assertEquals(errorCode, response.errorCode());
        return response;
    }
}
