package com.quizarena.security.tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking JSSE host-control listener isolated from the gameplay NIO selector. */
public final class TlsHostServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TlsHostServer.class.getName());

    private final TlsServerConfig config;
    private final SSLContext context;
    private final TlsRequestHandler handler;
    private final Semaphore permits;
    private final Set<TlsHostSession> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService workers;
    private SSLServerSocket listener;
    private Thread acceptThread;

    public TlsHostServer(TlsServerConfig config, SSLContext context, TlsRequestHandler handler) {
        this.config = Objects.requireNonNull(config, "config");
        this.context = Objects.requireNonNull(context, "context");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.permits = new Semaphore(config.maxConnections());
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("TLS server is already running");
        try {
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            listener.setEnabledProtocols(new String[] { "TLSv1.3" });
            listener.setNeedClientAuth(false);
            listener.bind(new InetSocketAddress(config.bindHost(), config.port()));
            workers = Executors.newCachedThreadPool(Thread.ofPlatform().daemon()
                    .name("quiz-tls-session-", 0).factory());
            acceptThread = Thread.ofPlatform().daemon().name("quiz-tls-accept").start(this::acceptLoop);
        } catch (IOException | RuntimeException failure) {
            running.set(false);
            closeListener();
            throw failure;
        }
    }

    public int boundPort() {
        SSLServerSocket current = listener;
        if (current == null || !current.isBound()) return -1;
        return current.getLocalPort();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                SSLSocket socket = (SSLSocket) listener.accept();
                socket.setEnabledProtocols(new String[] { "TLSv1.3" });
                socket.setSoTimeout(Math.toIntExact(config.socketTimeout().toMillis()));
                if (!permits.tryAcquire()) {
                    socket.close();
                    continue;
                }
                TlsHostSession session = new TlsHostSession(socket, handler);
                sessions.add(session);
                workers.execute(() -> runSession(session));
            } catch (SocketException failure) {
                if (running.get()) LOG.log(System.Logger.Level.WARNING, "TLS accept failed", failure);
            } catch (IOException | RuntimeException failure) {
                if (running.get()) LOG.log(System.Logger.Level.WARNING, "TLS accept failed", failure);
            }
        }
    }

    private void runSession(TlsHostSession session) {
        try (session) {
            session.run();
        } catch (Exception failure) {
            if (running.get()) LOG.log(System.Logger.Level.DEBUG, "TLS session ended", failure);
        } finally {
            sessions.remove(session);
            permits.release();
        }
    }

    @Override public synchronized void close() {
        if (!running.getAndSet(false)) return;
        closeListener();
        sessions.forEach(TlsHostSession::close);
        sessions.clear();
        if (workers != null) workers.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }

    private void closeListener() {
        if (listener == null) return;
        try { listener.close(); } catch (IOException ignored) { }
    }
}
