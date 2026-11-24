package com.quizarena.server;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;

/**
 * Everything the server knows about one gameplay TCP connection.
 *
 * Thread ownership (see blueprint section 5.2):
 * <ul>
 *   <li>Selector thread only: the {@link FrameDecoder}, the SelectionKey, the partially
 *       written buffer and the inbound rate window.</li>
 *   <li>Any thread: {@link #send}, {@link #sendFrame}, the identity getters.</li>
 *   <li>One worker at a time: queued request tasks ({@link #submit}) run strictly in
 *       arrival order, so a client's pipelined requests are never reordered.</li>
 * </ul>
 *
 * Identity (playerId / roomCode) is bound to this connection by the room registry.
 * It is never taken from a client-supplied field.
 */
public final class ClientSession {

    /** Maximum request tasks waiting for a worker before the client is considered flooding. */
    static final int MAX_PENDING_REQUESTS = 64;

    private static final System.Logger LOG = System.getLogger(ClientSession.class.getName());

    private final String connectionId = UUID.randomUUID().toString().substring(0, 8);
    private final String remote;
    private final SocketChannel channel;
    private final SessionHost host;
    private final int outboundLimit;
    private final FrameDecoder decoder = new FrameDecoder();

    // Outbound: producers append, the selector drains.
    private final Queue<byte[]> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicInteger outboundDepth = new AtomicInteger();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    // Inbound request ordering: guarded by "inbox".
    private final Queue<Runnable> inbox = new ArrayDeque<>();
    private boolean draining;

    private volatile String playerId;
    private volatile String playerName;
    private volatile String roomCode;
    private volatile long lastReadNanos = System.nanoTime();

    // Selector-thread-only state.
    private SelectionKey key;
    private ByteBuffer writing;
    private long rateWindowStartNanos = System.nanoTime();
    private int rateWindowCount;

    ClientSession(SocketChannel channel, String remote, SessionHost host, int outboundLimit) {
        this.channel = channel;
        this.remote = remote;
        this.host = host;
        this.outboundLimit = outboundLimit;
    }

    // ---------------------------------------------------------------- identity

    public String connectionId() {
        return connectionId;
    }

    public String remote() {
        return remote;
    }

    /** Null until JOIN_ROOM succeeds. */
    public String playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    /** Null until JOIN_ROOM succeeds. */
    public String roomCode() {
        return roomCode;
    }

    void bindToRoom(String roomCode, String playerId, String playerName) {
        this.playerName = playerName;
        this.playerId = playerId;
        this.roomCode = roomCode;
    }

    void clearRoom() {
        this.roomCode = null;
        this.playerId = null;
        this.playerName = null;
    }

    // ---------------------------------------------------------------- outbound (any thread)

    /** Encodes and queues a message. Returns false if it could not be queued. */
    public boolean send(ProtocolMessage message) {
        try {
            return sendFrame(MessageCodec.encodeLine(message));
        } catch (Exception failure) {
            // Oversized or unserializable message: a server bug, not a client fault.
            LOG.log(System.Logger.Level.WARNING,
                    "Dropping unencodable " + message.type() + " for " + connectionId, failure);
            return false;
        }
    }

    /**
     * Queues an already-encoded frame (including its '\n'). Broadcasts encode once and
     * share the same immutable array across sessions. Never blocks and never writes to the socket.
     * A client that falls {@code outboundLimit} frames behind is disconnected instead of
     * letting memory grow without bound.
     */
    boolean sendFrame(byte[] frame) {
        if (closed.get() || closeRequested.get()) {
            return false;
        }
        if (outboundDepth.incrementAndGet() > outboundLimit) {
            outboundDepth.decrementAndGet();
            if (closeRequested.compareAndSet(false, true)) {
                host.requestClose(this, "outbound queue overflow (slow consumer)");
            }
            return false;
        }
        outbound.add(frame);
        if (flushScheduled.compareAndSet(false, true)) {
            host.scheduleFlush(this);
        }
        return true;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Returns true only for the caller that actually closed the session. */
    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    // ---------------------------------------------------------------- inbound ordering

    /**
     * Runs {@code task} on the pool after every earlier task of this session has finished.
     * Returns false if too many tasks are already waiting.
     */
    boolean submit(Runnable task, Executor pool) {
        synchronized (inbox) {
            if (inbox.size() >= MAX_PENDING_REQUESTS) {
                return false;
            }
            inbox.add(task);
            if (draining) {
                return true;
            }
            draining = true;
        }
        try {        } catch (RejectedExecutionException shuttingDown) {
            synchronized (inbox) {
                draining = false;
                inbox.clear();
            }
            return false;
        }
        return true;
    }

    private void drainInbox() {
        while (true) {
            Runnable task;
            synchronized (inbox) {
                task = inbox.poll();
                if (task == null) {
                    draining = false;
                    return;
                }
            }
            try {
                task.run();
            } catch (Throwable failure) {
                LOG.log(System.Logger.Level.ERROR, "Request task failed for " + connectionId, failure);
            }
        }
    }

    // ---------------------------------------------------------------- selector-thread-only

    SocketChannel channel() {
        return channel;
    }

    FrameDecoder decoder() {
        return decoder;
    }

    void attach(SelectionKey key) {
        this.key = key;
    }

    SelectionKey key() {
        return key;
    }

    void touch() {
        lastReadNanos = System.nanoTime();
    }

    long lastReadNanos() {
        return lastReadNanos;
    }

    /** Fixed one-second window limiter for inbound frames. */
    boolean allowFrame(long nowNanos, int perSecond) {
        if (nowNanos - rateWindowStartNanos >= 1_000_000_000L) {
            rateWindowStartNanos = nowNanos;
            rateWindowCount = 0;
        }
        return ++rateWindowCount <= perSecond;
    }

    /** Called by the selector just before draining so a later producer re-schedules a flush. */
    void clearFlushScheduled() {
        flushScheduled.set(false);
    }

    /** The buffer currently being written, taking the next queued frame if none is in flight. */
    ByteBuffer currentWrite() {
        if (writing == null) {
            byte[] next = outbound.poll();
            if (next != null) {
                writing = ByteBuffer.wrap(next);
            }
        }
        return writing;
    }

    /** The in-flight buffer has been fully written. */
    void finishWrite() {
        writing = null;
        outboundDepth.decrementAndGet();
    }

    int outboundDepth() {
        return outboundDepth.get();
    }

    /** Test hook: remove and return the next queued outbound frame without a socket. */
    byte[] pollOutboundForTest() {
        byte[] frame = outbound.poll();
        if (frame != null) {
            outboundDepth.decrementAndGet();
        }
        return frame;
    }
}
