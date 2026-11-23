package com.quizarena.client.network;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** One reusable gameplay TCP connection. See docs/MEMBER2.md for callback and lifecycle rules. */
public final class TcpQuizClient implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(TcpQuizClient.class.getName());
    private static final int REQUEST_LIMIT = 128;
    private static final int EVENT_QUEUE_LIMIT = 256;

    private final Object lock = new Object();
    private final int connectTimeoutMillis;
    private final long requestTimeoutNanos;
    private final List<Subscription<ProtocolMessage>> eventListeners = new CopyOnWriteArrayList<>();
    private final List<Subscription<IOException>> lossListeners = new CopyOnWriteArrayList<>();

    // The lock protects the connection lifecycle, the retiring event worker, and pending requests.
    private Session current;
    private Session drainingEvents;
    private long generation;
    private boolean closed;

    public TcpQuizClient() {
        this(Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    public TcpQuizClient(Duration connectTimeout, Duration requestTimeout) {
        long connectMillis = connectTimeout.toMillis();
        if (connectMillis < 1 || connectMillis > Integer.MAX_VALUE || requestTimeout.toNanos() < 1) {
            throw new IllegalArgumentException("Expected a positive connect timeout in milliseconds and request timeout");
        }
        connectTimeoutMillis = (int) connectMillis;
        requestTimeoutNanos = requestTimeout.toNanos();
    }

    /** Resolves the address and connects on a background thread. Cancel the future to abandon the attempt. */
    public CompletableFuture<Void> connect(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A server host and valid TCP port are required"));
        }
        synchronized (lock) {
            if (closed || current != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(closed ? "Client is closed"
                        : "A connection is already open or being established"));
            }
            discardDrainingEvents();
            Session session = new Session(++generation);
            current = session;
            session.connection.whenComplete((ignored, failure) -> {
                if (session.connection.isCancelled()) {
                    end(session, new IOException("Connection attempt cancelled"), false);
                }
            });
            session.writer.execute(() -> open(session, host, port));
            return session.connection;
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return !closed && current != null && current.connected;
        }
    }

    /** Sends a request with a caller-supplied ID unique to this connection. */
    public CompletableFuture<ProtocolMessage> request(ProtocolMessage message) {
        Objects.requireNonNull(message, "request");
        String id = message.requestId();
        if (id == null || id.isBlank() || message.status() != null || message.errorCode() != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected a request with a nonblank requestId"));
        }

        synchronized (lock) {
            Session session = current;
            if (closed || session == null || !session.connected) {
                return CompletableFuture.failedFuture(new IOException("Client is disconnected"));
            }
            if (session.pending.containsKey(id)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Request ID is already pending: " + id));
            }
            if (session.pending.size() >= REQUEST_LIMIT || session.writer.getQueue().remainingCapacity() == 0) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Too many outstanding TCP requests"));
            }

            PendingRequest pending = new PendingRequest(id);
            pending.write = () -> write(session, pending, message);
            session.pending.put(id, pending);
            pending.result.whenComplete((reply, failure) -> removePending(session, pending));
            pending.timeout = session.timeouts.schedule(() -> finish(session, pending, null,
                    new TimeoutException("Timed out waiting for response to " + id)), requestTimeoutNanos, TimeUnit.NANOSECONDS);
            session.writer.execute(pending.write);
            return pending.result;
        }
    }

    /** Delivers pushed messages in wire order on a background event thread. */
    public AutoCloseable subscribe(Consumer<ProtocolMessage> listener) {
        return subscribe(eventListeners, listener);
    }

    /** Reports unexpected loss of an established connection after its accepted events. */
    public AutoCloseable onConnectionLost(Consumer<IOException> listener) {
        return subscribe(lossListeners, listener);
    }

    /** Cancels the current connection or attempt. The client can connect again. */
    public void disconnect() {
        Session session;
        synchronized (lock) {
            discardDrainingEvents();
            session = current;
        }
        if (session != null) {
            end(session, new IOException("Client disconnected"), false);
        }
    }

    /** Releases the connection and subscriptions permanently. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            eventListeners.clear();
            lossListeners.clear();
        }
        disconnect();
    }

    private void open(Session session, String host, int port) {
        try {
            var address = new InetSocketAddress(host, port);
            if (address.isUnresolved()) {
                throw new UnknownHostException(host);
            }
            session.socket.connect(address, connectTimeoutMillis);
            session.socket.setTcpNoDelay(true);
            synchronized (lock) {
                if (closed || current != session) {
                    return;
                }
                session.connected = true;
                threadFactory("reader").newThread(() -> read(session)).start();
                settle(session.connection, null, null);
            }
        } catch (IOException | RuntimeException failure) {
            IOException cause = failure instanceof IOException io ? io : new IOException("Could not open TCP connection", failure);
            end(session, cause, false);
        }
    }

    private void write(Session session, PendingRequest pending, ProtocolMessage message) {
        if (pending.result.isDone()) {
            return;
        }

        byte[] frame;
        try {
            frame = MessageCodec.encodeLine(message);
        } catch (IOException | RuntimeException failure) {
            finish(session, pending, null, failure);
            return;
        }

        synchronized (lock) {
            if (current != session || session.pending.get(pending.id) != pending || pending.result.isDone()) {
                return;
            }
        }
        try {
            session.socket.getOutputStream().write(frame);
        } catch (IOException failure) {
            end(session, failure, true);
        }
    }

    private void read(Session session) {
        try {
            var reader = new JsonFrameReader(session.socket.getInputStream());
            while (true) {
                receive(session, reader.read());
            }
        } catch (IOException failure) {
            end(session, failure, true);
        }
    }

    private void receive(Session session, ProtocolMessage message) throws IOException {
        if (message.status() == null) {
            synchronized (lock) {
                if (current == session && !eventListeners.isEmpty()) {
                    // Keep one queue slot for the terminal connection-loss notification.
                    if (session.events.getQueue().size() >= EVENT_QUEUE_LIMIT) {
                        throw new IOException("Incoming TCP event queue is full");
                    }
                    enqueue(session, List.copyOf(eventListeners), message);
                }
            }
            return;
        }

        String id = message.requestId();
        if (id == null || id.isBlank() || !("OK".equals(message.status()) || "ERROR".equals(message.status()))) {
            throw new IOException("Invalid TCP response envelope");
        }
        PendingRequest pending;
        synchronized (lock) {
            pending = session.pending.get(id);
        }
        if (pending != null) {
            finish(session, pending, message, null);
        }
    }

    private void finish(Session session, PendingRequest pending, ProtocolMessage reply, Throwable failure) {
        if (removePending(session, pending)) {
            settle(pending.result, reply, failure);
        }
    }

    private boolean removePending(Session session, PendingRequest pending) {
        synchronized (lock) {
            if (!session.pending.remove(pending.id, pending)) {
                return false;
            }
            pending.timeout.cancel(false);
            session.writer.remove(pending.write);
            return true;
        }
    }

    private void end(Session session, IOException cause, boolean unexpected) {
        List<PendingRequest> outstanding;
        boolean wasConnected;
        synchronized (lock) {
            if (current != session) {
                if (!unexpected && drainingEvents == session) {
                    discardDrainingEvents();
                }
                return;
            }
            current = null;
            wasConnected = session.connected;
            outstanding = List.copyOf(session.pending.values());
            session.pending.clear();
            if (unexpected && wasConnected && !closed) {
                drainConnectionLoss(session, cause);
            } else {
                session.discardEvents = true;
                session.events.shutdownNow();
            }
        }

        try {
            session.socket.close();
        } catch (IOException failure) {
            cause.addSuppressed(failure);
        }
        session.writer.shutdownNow();
        session.timeouts.shutdownNow();
        if (!wasConnected) {
            settle(session.connection, null, cause);
        }
        for (PendingRequest pending : outstanding) {
            pending.timeout.cancel(false);
            settle(pending.result, null, cause);
        }
    }

    private void drainConnectionLoss(Session session, IOException cause) {
        drainingEvents = session;
        List<Subscription<IOException>> subscriptions = List.copyOf(lossListeners);
        // The last queue slot is reserved for the ordered loss notification and worker cleanup.
        session.events.execute(() -> {
            try {
                deliver(session, subscriptions, cause);
            } finally {
                synchronized (lock) {
                    if (drainingEvents == session) {
                        drainingEvents = null;
                    }
                }
            }
        });
        session.events.shutdown();
    }

    private void discardDrainingEvents() {
        if (drainingEvents != null) {
            drainingEvents.discardEvents = true;
            drainingEvents.events.shutdownNow();
            drainingEvents = null;
        }
    }

    private <T> AutoCloseable subscribe(List<Subscription<T>> listeners, Consumer<T> listener) {
        var subscription = new Subscription<>(Objects.requireNonNull(listener, "listener"));
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Client is closed");
            }
            listeners.add(subscription);
        }
        return () -> {
            subscription.active = false;
            listeners.remove(subscription);
        };
    }

    private <T> void enqueue(Session session, List<Subscription<T>> subscriptions, T value) {
        session.events.execute(() -> deliver(session, subscriptions, value));
    }

    private <T> void deliver(Session session, List<Subscription<T>> subscriptions, T value) {
        for (Subscription<T> subscription : subscriptions) {
            synchronized (lock) {
                if (closed || session.discardEvents || session.generation != generation) {
                    return;
                }
            }
            if (subscription.active) {
                try {
                    subscription.listener.accept(value);
                } catch (RuntimeException failure) {
                    LOGGER.log(System.Logger.Level.WARNING, "TCP client listener failed", failure);
                }
            }
        }
    }

    private static <T> void settle(CompletableFuture<T> future, T value, Throwable failure) {
        // Completing a pending future can run caller continuations. Keep that work off the I/O threads.
        ForkJoinPool.commonPool().execute(() -> {
            if (failure == null) {
                future.complete(value);
            } else {
                future.completeExceptionally(failure);
            }
        });
    }

    private static ThreadFactory threadFactory(String purpose) {
        return Thread.ofPlatform().daemon().name("quizarena-tcp-" + purpose).factory();
    }

    private static ThreadPoolExecutor worker(String purpose, int queueCapacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory(purpose));
    }

    private static final class Session {
        final long generation;
        final Socket socket = new Socket();
        final CompletableFuture<Void> connection = new CompletableFuture<>();
        final Map<String, PendingRequest> pending = new HashMap<>();
        final ThreadPoolExecutor writer = worker("writer", REQUEST_LIMIT);
        final ThreadPoolExecutor events = worker("events", EVENT_QUEUE_LIMIT + 1);
        final ScheduledThreadPoolExecutor timeouts = new ScheduledThreadPoolExecutor(1, threadFactory("timeouts"));
        boolean connected;
        boolean discardEvents;

        Session(long generation) {
            this.generation = generation;
            timeouts.setRemoveOnCancelPolicy(true);
        }
    }

    private static final class PendingRequest {
        final String id;
        final CompletableFuture<ProtocolMessage> result = new CompletableFuture<>();
        Runnable write;
        ScheduledFuture<?> timeout;

        PendingRequest(String id) {
            this.id = id;
        }
    }

    private static final class Subscription<T> {
        final Consumer<T> listener;
        volatile boolean active = true;

        Subscription(Consumer<T> listener) {
            this.listener = listener;
        }
    }
}
