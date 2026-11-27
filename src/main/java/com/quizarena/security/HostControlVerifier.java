package com.quizarena.security;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.tls.TlsFrameReader;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.Console;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Map;

import static com.quizarena.protocol.MessageTypes.*;

/** Local smoke-test tool that verifies login and room creation without exposing passwords in arguments. */
public final class HostControlVerifier {
    private HostControlVerifier() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: HostControlVerifier [username]");
        String username = args.length == 1 ? args[0] : "host-admin";
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Run this tool in a real terminal");

        char[] storePassword = console.readPassword("TLS keystore password: ");
        char[] hostPassword = console.readPassword("QuizArena host password: ");
        try {
            SSLContext context = trustingContext(Path.of(requiredEnv("QUIZ_TLS_KEYSTORE_PATH")), storePassword);
            String host = System.getenv().getOrDefault("QUIZ_TLS_BIND_HOST", "127.0.0.1");
            if ("0.0.0.0".equals(host)) host = "127.0.0.1";
            int port = Integer.parseInt(System.getenv().getOrDefault("QUIZ_TLS_PORT", "8443"));

            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port)) {
                socket.setEnabledProtocols(new String[] { "TLSv1.3" });
                socket.startHandshake();
                var frames = new TlsFrameReader(new BufferedInputStream(socket.getInputStream()));

                send(socket, new ProtocolMessage(HOST_LOGIN, "verify-login", null, null, null,
                        Map.of("username", username, "password", new String(hostPassword))));
                requireOk(receive(frames), HOST_LOGIN_RESULT);

                send(socket, new ProtocolMessage(CREATE_ROOM, "verify-room", null, null, null, Map.of()));
                ProtocolMessage room = receive(frames);
                requireOk(room, CREATE_ROOM_RESULT);
                console.printf("TLS %s login succeeded; created room %s.%n",
                        socket.getSession().getProtocol(), room.payload().get("roomCode"));
            }
        } finally {
            if (storePassword != null) Arrays.fill(storePassword, '\0');
            if (hostPassword != null) Arrays.fill(hostPassword, '\0');
        }
    }

    private static SSLContext trustingContext(Path keyStorePath, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) { store.load(input, password); }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static void send(SSLSocket socket, ProtocolMessage request) throws Exception {
        socket.getOutputStream().write(MessageCodec.encodeLine(request));
        socket.getOutputStream().flush();
    }

    private static ProtocolMessage receive(TlsFrameReader frames) throws Exception {
        byte[] frame = frames.nextFrame();
        if (frame == null) throw new IllegalStateException("TLS server closed the connection without a reply");
        return MessageCodec.decodeJson(frame);
    }

    private static void requireOk(ProtocolMessage response, String expectedType) {
        if (!expectedType.equals(response.type()) || !"OK".equals(response.status())) {
            throw new IllegalStateException(expectedType + " failed: " + response.errorCode());
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
