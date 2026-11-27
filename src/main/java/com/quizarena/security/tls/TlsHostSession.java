package com.quizarena.security.tls;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.AuthenticatedHost;

import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One authenticated-encryption transport session; authorization is added by the command layer. */
public final class TlsHostSession implements TlsSessionContext, AutoCloseable {
    private final SSLSocket socket;
    private final TlsRequestHandler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<AuthenticatedHost> authenticatedHost = new AtomicReference<>();
    private BufferedOutputStream output;

    TlsHostSession(SSLSocket socket, TlsRequestHandler handler) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    void run() throws Exception {
        socket.startHandshake();
        output = new BufferedOutputStream(socket.getOutputStream());
        var frames = new TlsFrameReader(new BufferedInputStream(socket.getInputStream()));
        byte[] frame;
        while (!closed.get() && (frame = frames.nextFrame()) != null) {
            handler.handle(MessageCodec.decodeJson(frame), this);
        }
    }

    @Override public synchronized void send(ProtocolMessage message) throws IOException {
        if (closed.get() || output == null) throw new IOException("TLS session is closed");
        output.write(MessageCodec.encodeLine(message));
        output.flush();
    }

    @Override public Optional<AuthenticatedHost> authenticatedHost() {
        return Optional.ofNullable(authenticatedHost.get());
    }

    @Override public boolean authenticate(AuthenticatedHost host) {
        return authenticatedHost.compareAndSet(null, Objects.requireNonNull(host, "host"));
    }

    @Override public SocketAddress remoteAddress() {
        return socket.getRemoteSocketAddress();
    }

    @Override public String protocol() {
        return socket.getSession().getProtocol();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
