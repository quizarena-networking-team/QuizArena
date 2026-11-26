package com.quizarena.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizarena.metrics.NetworkEvent;
import com.quizarena.metrics.NetworkEventBus;
import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The QuizArena gameplay gateway: one non-blocking reactor for every client on TCP/5000.
 *
 * <h2>Threading model (blueprint 5.2 / 9.1 / 9.2)</h2>
 * <ul>
 *   <li><b>Selector thread</b> ("quiz-nio-selector"): the only thread that touches the Selector,
 *       SelectionKeys and SocketChannels. It accepts connections, reads bytes, reassembles and
 *       decodes frames, drains outbound queues and closes sessions. It never runs game logic,
 *       JDBC, TLS or anything else that can block.</li>
 *   <li><b>Worker pool</b> ("quiz-nio-worker-N"): runs {@link MessageDispatcher} handlers. Workers
 *       never write to a channel or change interest ops. They enqueue frames and post a task into the
 *       {@code mailbox}; {@code selector.wakeup()} makes the selector pick it up.</li>
 * </ul>
 *
 * <h2>Per-connection lifecycle</h2>
 * accept -> register OP_READ -> read into the session's ByteBuffer -> frame (flip/compact) ->
 * decode -> per-session ordered dispatch to a worker -> reply/event frames queue up -> selector writes
 * them, enabling OP_WRITE only while the socket cannot take more -> on EOF, error, malformed frame,
 * overflow or idle timeout: cancel key, close channel, drop room membership, publish DISCONNECT.
 */
public final class NioQuizServer implements AutoCloseable, SessionHost {

    private static final System.Logger LOG = System.getLogger(NioQuizServer.class.getName());
    private static final long SELECT_TIMEOUT_MS = 500;
    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final ObjectMapper SALVAGE_MAPPER = new ObjectMapper();

    private final ServerConfig config;
    private final MessageDispatcher dispatcher;
    private final RoomRegistry rooms;
    private final NetworkEventBus events;

    // Selector-thread-owned.
    private final Set<ClientSession> sessions = new HashSet<>();
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private long lastSweepNanos = System.nanoTime();

    // Cross-thread.
    private final ConcurrentLinkedQueue<Runnable> mailbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private volatile boolean running;
    private volatile boolean shuttingDown;
    private volatile int boundPort = -1;
    private Thread selectorThread;
    private ExecutorService workers;

    public NioQuizServer(ServerConfig config, MessageDispatcher dispatcher, RoomRegistry rooms, NetworkEventBus events) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.rooms = rooms;
        this.events = events;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Binds the port and starts the selector thread. Returns once the server is accepting. */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Server already started");
        }
        selector = Selector.open();
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(config.bindHost(), config.tcpPort()));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(serverChannel);
            closeQuietly(selector);
            throw failure;
        }
        boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        workers = Executors.newFixedThreadPool(config.workerThreads(), namedDaemonThreads("quiz-nio-worker-"));
        shuttingDown = false;
        running = true;
        selectorThread = new Thread(this::runLoop, "quiz-nio-selector");
        selectorThread.setDaemon(true);
        selectorThread.start();
        events.publish(NetworkEvent.Kind.SERVER_STARTED, null, config.bindHost() + ":" + boundPort, 0,
                "NIO gameplay server listening");
    }

    /** The actual TCP port (useful when configured with port 0). */
    public int boundPort() {
        return boundPort;
    }

    public int connectionCount() {
        return connectionCount.get();
    }

    /** Stops accepting, disconnects every client and releases all threads. Idempotent. */
    public void stop() {
        Thread loop;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            shuttingDown = true;
            loop = selectorThread;
        }
        wakeup();
        try {
            loop.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ---------------------------------------------------------------- SessionHost (any thread)

    @Override
    public void scheduleFlush(ClientSession session) {
        execute(() -> flush(session));
    }

    @Override
    public void requestClose(ClientSession session, String reason) {
        execute(() -> closeNow(session, reason));
    }

    /** Thread-safe mailbox: run {@code task} on the selector thread soon. */
    private void execute(Runnable task) {
        mailbox.add(task);
        wakeup();
    }

    private void wakeup() {
        Selector current = selector;
        if (current != null) {
            current.wakeup();
        }
    }

    // ---------------------------------------------------------------- selector loop (selector thread only)

    private void runLoop() {
        try {
            while (running) {
                selector.select(SELECT_TIMEOUT_MS);
                runMailbox();

                Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
                while (ready.hasNext()) {
                    SelectionKey key = ready.next();
                    ready.remove();
                    handleKey(key);
                }
                sweepIdle();
            }
        } catch (Throwable fatal) {
            LOG.log(System.Logger.Level.ERROR, "Selector loop crashed", fatal);
            events.publish(NetworkEvent.Kind.ERROR, null, null, 0, "selector loop crashed: " + fatal);
        } finally {
            running = false;
            shutdownResources();
        }
    }

    private void runMailbox() {
        Runnable task;
        while ((task = mailbox.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.ERROR, "Mailbox task failed", failure);
            }
        }
    }

    /** One bad connection must never take the loop down (blueprint 9.3: disconnect vs broadcast). */
    private void handleKey(SelectionKey key) {
        try {
            if (!key.isValid()) {
                return;
            }
            if (key.isAcceptable()) {
                accept();
                return;
            }
            ClientSession session = (ClientSession) key.attachment();
            if (key.isReadable()) {
                read(session);
            }
            if (key.isValid() && key.isWritable()) {
                flush(session);
            }
        } catch (CancelledKeyException alreadyClosed) {
            // The session was closed while this key was being processed.
        } catch (IOException failure) {
            Object attachment = key.attachment();
            if (attachment instanceof ClientSession session) {
                closeNow(session, "I/O error: " + failure.getClass().getSimpleName());
            } else {
                events.publish(NetworkEvent.Kind.ERROR, null, null, 0, "accept failed: " + failure);
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Unexpected failure handling a connection", failure);
            if (key.attachment() instanceof ClientSession session) {
                closeNow(session, "internal error");
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel channel;
        while ((channel = serverChannel.accept()) != null) {
            String remote = String.valueOf(channel.getRemoteAddress());
            if (sessions.size() >= config.maxConnections()) {
                events.publish(NetworkEvent.Kind.ERROR, null, remote, 0, "connection limit reached; refused");
                closeQuietly(channel);
                continue;
            }
            try {
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                ClientSession session = new ClientSession(channel, remote, this, config.outboundQueueLimit());
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ, session);
                session.attach(key);
                sessions.add(session);
                connectionCount.incrementAndGet();
                events.publish(NetworkEvent.Kind.ACCEPT, session.connectionId(), remote, 0, "client connected");
            } catch (IOException | RuntimeException failure) {
                events.publish(NetworkEvent.Kind.ERROR, null, remote, 0, "could not register client: " + failure);
                closeQuietly(channel);
            }
        }
    }

    private void read(ClientSession session) throws IOException {
        FrameDecoder decoder = session.decoder();
        int count = session.channel().read(decoder.writeBuffer());
        if (count < 0) {
            closeNow(session, "client closed the connection");
            return;
        }
        if (count == 0) {
            return;
        }
        session.touch();
        events.publish(NetworkEvent.Kind.READ, session.connectionId(), session.remote(), count, null);

        List<byte[]> frames;
        try {
            frames = decoder.drainFrames();
        } catch (FrameDecoder.FrameTooLargeException oversized) {
            reject(session, oversized.getMessage());
            closeNow(session, "oversized frame");
            return;
        }
        for (byte[] frame : frames) {
            if (session.isClosed()) {
                return;
            }
            if (!session.allowFrame(System.nanoTime(), config.inboundFramesPerSecond())) {
                reject(session, "inbound rate limit exceeded");
                closeNow(session, "inbound rate limit exceeded");
                return;
            }
            handleFrame(session, frame);
        }
    }

    private void handleFrame(ClientSession session, byte[] frame) {
        ProtocolMessage request;
        try {
            request = MessageCodec.decodeJson(frame);
        } catch (IOException invalid) {
            rejectMalformed(session, frame);
            return;
        }
        String requestId = request.requestId();
        if (!MessageDispatcher.isWellFormedType(request.type())
                || requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            // A request we cannot even correlate a reply to: protocol violation.
            reject(session, "request without a valid type/requestId");
            closeNow(session, "protocol violation");
            return;
        }
        SessionContext context = new SessionContext(session, rooms);
        if (!session.submit(() -> dispatcher.dispatch(request, context), workers)) {
            reject(session, "too many unprocessed requests");
            closeNow(session, "inbound backlog overflow");
        }
    }

    /**
     * Invalid envelope. If the frame is still JSON with a usable type and requestId we answer
     * BAD_REQUEST and keep the connection; otherwise the stream is garbage and we close it.
     */
    private void rejectMalformed(ClientSession session, byte[] frame) {
        reject(session, "malformed message");
        try {
            JsonNode node = SALVAGE_MAPPER.readTree(frame);
            String type = node.path("type").isTextual() ? node.get("type").asText() : null;
            String requestId = node.path("requestId").isTextual() ? node.get("requestId").asText() : null;
            if (MessageDispatcher.isWellFormedType(type) && requestId != null && !requestId.isBlank()
                    && requestId.length() <= MAX_REQUEST_ID_LENGTH) {
                session.send(new ProtocolMessage(MessageDispatcher.replyTypeFor(type), requestId, null,
                        "ERROR", "BAD_REQUEST", Map.of("message", "Malformed message")));
                return;
            }
        } catch (IOException notJson) {
            // fall through to close
        }
        closeNow(session, "malformed frame");
    }

    private void reject(ClientSession session, String detail) {
        events.publish(NetworkEvent.Kind.FRAME_REJECTED, session.connectionId(), session.remote(), 0, detail);
    }

    // ---------------------------------------------------------------- writing (selector thread only)

    /**
     * Drains the session's outbound queue. Handles partial writes: if the socket takes only part of a
     * buffer we keep the remainder and ask for OP_WRITE; once everything is flushed OP_WRITE is removed
     * so the selector does not spin on an always-writable socket.
     */
    private void flush(ClientSession session) {
        if (session.isClosed()) {
            return;
        }
        session.clearFlushScheduled();
        SelectionKey key = session.key();
        long written = 0;
        try {
            while (true) {
                ByteBuffer pending = session.currentWrite();
                if (pending == null) {
                    if (key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) != 0) {
                        key.interestOps(SelectionKey.OP_READ);
                    }
                    break;
                }
                written += session.channel().write(pending);
                if (pending.hasRemaining()) {
                    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    break;
                }
                session.finishWrite();
            }
        } catch (IOException | CancelledKeyException failure) {
            closeNow(session, "write failed: " + failure.getClass().getSimpleName());
            return;
        } finally {
            if (written > 0) {
                events.publish(NetworkEvent.Kind.WRITE, session.connectionId(), session.remote(), written, null);
            }
        }
    }

    // ---------------------------------------------------------------- closing (selector thread only)

    /** Idempotent. Cancels the key, closes the channel, drops room membership and reports the disconnect. */
    private void closeNow(ClientSession session, String reason) {
        if (!session.markClosed()) {
            return;
        }
        if (sessions.remove(session)) {
            connectionCount.decrementAndGet();
        }
        SelectionKey key = session.key();
        if (key != null) {
            key.cancel();
        }
        closeQuietly(session.channel());
        if (!shuttingDown) {
            try {
                rooms.leave(session);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.ERROR, "Could not remove " + session.connectionId() + " from its room", failure);
            }
        }
        events.publish(NetworkEvent.Kind.DISCONNECT, session.connectionId(), session.remote(), 0, reason);
    }

    private void sweepIdle() {
        long timeoutNanos = config.idleTimeout().toNanos();
        long now = System.nanoTime();
        if (timeoutNanos == 0 || now - lastSweepNanos < SWEEP_INTERVAL_NANOS) {
            return;
        }
        lastSweepNanos = now;
        for (ClientSession session : new ArrayList<>(sessions)) {
            if (now - session.lastReadNanos() > timeoutNanos) {
                closeNow(session, "idle timeout");
            }
        }
    }

    private void shutdownResources() {
        shuttingDown = true;
        for (ClientSession session : new ArrayList<>(sessions)) {
            closeNow(session, "server shutdown");
        }
        closeQuietly(serverChannel);
        closeQuietly(selector);
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        events.publish(NetworkEvent.Kind.SERVER_STOPPED, null, null, 0, "NIO gameplay server stopped");
    }

    // ---------------------------------------------------------------- helpers

    private static ThreadFactory namedDaemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing useful to do: we are already tearing the resource down.
        }
    }
}
