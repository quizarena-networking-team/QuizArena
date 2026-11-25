package com.quizarena.metrics;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory bus for real networking events.
 *
 * Keeps a bounded ring of the most recent events (for an inspector snapshot) and
 * running counters per event kind. Listeners are called synchronously on the
 * publishing thread, which may be the NIO selector thread, so they must return
 * quickly and must never block.
 */
public final class NetworkEventBus {

    public static final int DEFAULT_CAPACITY = 500;

    /** Handle returned by {@link #subscribe}; closing it removes the listener. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private static final System.Logger LOG = System.getLogger(NetworkEventBus.class.getName());

    private final int capacity;
    private final Deque<NetworkEvent> recent = new ArrayDeque<>();
    private final List<Consumer<NetworkEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLongArray counts = new AtomicLongArray(NetworkEvent.Kind.values().length);
    private final AtomicLongArray bytes = new AtomicLongArray(NetworkEvent.Kind.values().length);

    public NetworkEventBus() {
        this(DEFAULT_CAPACITY);
    }

    public NetworkEventBus(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void publish(NetworkEvent.Kind kind, String connectionId, String remote, long byteCount, String detail) {
        publish(new NetworkEvent(Instant.now(), kind, connectionId, remote, byteCount, detail));
    }

    public void publish(NetworkEvent event) {
        counts.incrementAndGet(event.kind().ordinal());
        bytes.addAndGet(event.kind().ordinal(), event.bytes());
        synchronized (recent) {
            if (recent.size() == capacity) {
                recent.removeFirst();
            }
            recent.addLast(event);
        }
        for (Consumer<NetworkEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Network event listener failed", failure);
            }
        }
    }

    public Subscription subscribe(Consumer<NetworkEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Immutable snapshot, oldest first, of at most {@code capacity} recent events. */
    public List<NetworkEvent> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    /** Total number of events of this kind published since the bus was created. */
    public long count(NetworkEvent.Kind kind) {
        return counts.get(kind.ordinal());
    }

    /** Total bytes reported by events of this kind (meaningful for READ and WRITE). */
    public long totalBytes(NetworkEvent.Kind kind) {
        return bytes.get(kind.ordinal());
    }
}
