package com.quizarena.client.network;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.quizarena.protocol.MessageTypes.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TcpQuizClientTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final String HOST = LOOPBACK.getHostAddress();

    @Test
    void connectsOnceAndDisconnectsCleanly() throws Exception {
        try (var client = new TcpQuizClient(); var server = new LoopbackServer()) {
            var connection = client.connect(HOST, server.port());
            assertInstanceOf(IllegalStateException.class, failureOf(client.connect(HOST, server.port())));
            server.accept();
            await(connection);
            assertTrue(client.isConnected());

            var request = client.request(ping("in-flight"));
            assertEquals("in-flight", server.receive().requestId());
            client.disconnect();
            assertFalse(client.isConnected());
            assertEquals("Client disconnected", failureOf(request).getMessage());
            assertNull(server.input.readLine(), "The peer should see a clean EOF");
            assertInstanceOf(IOException.class, failureOf(client.request(ping("offline"))));
        }
    }

    @Test
    void reportsFailedConnectionAndCanRetry() throws Exception {
        try (var client = new TcpQuizClient(); var unusedPort = new Socket(); var server = new LoopbackServer()) {
            // Reserve a loopback port without listening on it.
            unusedPort.bind(new InetSocketAddress(LOOPBACK, 0));
            assertInstanceOf(ConnectException.class, failureOf(client.connect(HOST, unusedPort.getLocalPort())));
            assertFalse(client.isConnected());
            server.connect(client);
            assertTrue(client.isConnected());
        }
    }

    @Test
    void closeIsPermanent() throws Exception {
        try (var client = new TcpQuizClient(); var server = new LoopbackServer()) {
            server.connect(client);
            client.close();
            assertFalse(client.isConnected());
            assertInstanceOf(IllegalStateException.class, failureOf(client.connect(HOST, server.port())));
            assertThrows(IllegalStateException.class, () -> client.subscribe(event -> {}));
            assertNull(server.input.readLine());
        }
    }

    @Test
    void correlatesConcurrentRequestsWithOutOfOrderResponses() throws Exception {
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var callers = Executors.newFixedThreadPool(8)) {
            server.connect(client);
            var start = new CountDownLatch(1);
            List<CompletableFuture<ProtocolMessage>> replies = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                var request = ping("request-" + i);
                replies.add(CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    return client.request(request);
                }, callers).thenCompose(reply -> reply));
            }
            start.countDown();

            List<ProtocolMessage> requests = new ArrayList<>();
            for (int i = 0; i < replies.size(); i++) {
                var request = server.receive();
                assertEquals(PING, request.type());
                requests.add(request);
            }
            for (var request : requests.reversed()) {
                server.send(pong(request.requestId()));
            }
            for (int i = 0; i < replies.size(); i++) {
                assertEquals(pong("request-" + i), await(replies.get(i)));
            }
        }
    }

    @Test
    void distinguishesPushesWithIdsFromDirectResponsesAndKeepsErrorReplies() throws Exception {
        var events = new LinkedBlockingQueue<ProtocolMessage>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var subscription = client.subscribe(events::add)) {
            server.connect(client);
            var request = client.request(new ProtocolMessage(JOIN_ROOM, "join", "ABC123", null,
                    null, Map.of("playerName", "Amal")));
            assertEquals("join", server.receive().requestId());
            assertInstanceOf(IllegalArgumentException.class, failureOf(client.request(ping("join"))));

            var push = new ProtocolMessage(LOBBY_UPDATED, "join", "ABC123", null, null, Map.of());
            server.send(push);
            assertEquals(push, take(events));
            assertFalse(request.isDone());

            var denied = new ProtocolMessage(JOIN_ROOM_RESULT, "join", "ABC123", "ERROR", "ROOM_NOT_FOUND", Map.of());
            server.send(denied);
            assertEquals(denied, await(request));
        }
    }

    @Test
    void assemblesPartialUtf8AndDeliversBatchedEventsInOrder() throws Exception {
        var firstListener = new LinkedBlockingQueue<ProtocolMessage>();
        var secondListener = new LinkedBlockingQueue<ProtocolMessage>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var first = client.subscribe(firstListener::add); var second = client.subscribe(secondListener::add)) {
            server.connect(client);
            var lobby = event(LOBBY_UPDATED, "Amal");
            var poll = event(POLL_STARTED, "Café");
            var closed = event(POLL_CLOSED, "Finished");
            byte[] frame = MessageCodec.encodeLine(poll);
            int cut = new String(frame, StandardCharsets.UTF_8).indexOf('é') + 1;

            server.write(MessageCodec.encodeLine(lobby), Arrays.copyOf(frame, cut));
            assertEquals(lobby, take(firstListener));
            assertTrue(firstListener.isEmpty());
            server.write(Arrays.copyOfRange(frame, cut, frame.length), MessageCodec.encodeLine(closed));

            assertEquals(poll, take(firstListener));
            assertEquals(closed, take(firstListener));
            assertEquals(lobby, take(secondListener));
            assertEquals(poll, take(secondListener));
            assertEquals(closed, take(secondListener));
        }
    }

    @Test
    void slowAndFailingListenersLeaveSocketReadsResponsive() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var events = new LinkedBlockingQueue<ProtocolMessage>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var slow = client.subscribe(event -> { entered.countDown(); awaitLatch(release); });
             var failing = client.subscribe(event -> { throw new IllegalStateException("Listener fixture failure"); });
             var healthy = client.subscribe(events::add)) {
            server.connect(client);
            try {
                server.send(event(LOBBY_UPDATED, "First"));
                awaitLatch(entered);
                var request = client.request(ping("while-busy"));
                server.send(pong(server.receive().requestId()));
                assertEquals(pong("while-busy"), await(request));
                assertTrue(events.isEmpty());
            } finally {
                release.countDown();
            }
            assertEquals(LOBBY_UPDATED, take(events).type());
            slow.close();
            failing.close();
            server.send(event(POLL_STARTED, "Second"));
            assertEquals(POLL_STARTED, take(events).type());
        }
    }

    @Test
    void unsubscribeSkipsAlreadyQueuedEvents() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var removed = new LinkedBlockingQueue<ProtocolMessage>();
        var active = new LinkedBlockingQueue<ProtocolMessage>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var blocker = client.subscribe(event -> { entered.countDown(); awaitLatch(release); });
             var subscription = client.subscribe(removed::add); var observer = client.subscribe(active::add)) {
            server.connect(client);
            try {
                server.send(event(LOBBY_UPDATED, "First"));
                awaitLatch(entered);
                var request = client.request(ping("fence"));
                server.write(MessageCodec.encodeLine(event(POLL_STARTED, "Queued")),
                        MessageCodec.encodeLine(pong(server.receive().requestId())));
                await(request);
                subscription.close();
            } finally {
                release.countDown();
            }
            assertEquals(LOBBY_UPDATED, take(active).type());
            assertEquals(POLL_STARTED, take(active).type());
            assertTrue(removed.isEmpty());
        }
    }

    @Test
    void reportsUnexpectedEofFailsPendingRequestsAndReconnects() throws Exception {
        var notices = new LinkedBlockingQueue<Object>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer(); var replacement = new LoopbackServer();
             var events = client.subscribe(notices::add); var loss = client.onConnectionLost(notices::add)) {
            server.connect(client);
            var first = client.request(ping("pending-one"));
            var second = client.request(ping("pending-two"));
            server.receive();
            server.receive();
            var roomClosed = event(ROOM_CLOSED, "Closing");
            server.send(roomClosed);
            server.peer.close();

            assertEquals(roomClosed, take(notices));
            assertInstanceOf(EOFException.class, take(notices));
            assertFalse(client.isConnected());
            assertInstanceOf(EOFException.class, failureOf(first));
            assertInstanceOf(EOFException.class, failureOf(second));

            replacement.connect(client);
            var next = client.request(ping("pending-one")); // IDs are scoped to a connection.
            replacement.send(pong(replacement.receive().requestId()));
            assertEquals(pong("pending-one"), await(next));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopsTheRetiredEventWorkerWhenClosingOrReconnecting(boolean reconnect) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var otherEvents = new LinkedBlockingQueue<ProtocolMessage>();
        var losses = new LinkedBlockingQueue<IOException>();
        var blocking = event(LOBBY_UPDATED, "Hold the old worker");
        try (var client = new TcpQuizClient(); var server = new LoopbackServer(); var replacement = new LoopbackServer();
             var subscription = client.subscribe(message -> {
                 if (!blocking.equals(message)) {
                     otherEvents.add(message);
                     return;
                 }
                 worker.set(Thread.currentThread());
                 entered.countDown();
                 try {
                     release.await();
                 } catch (InterruptedException expected) {
                     interrupted.countDown();
                 }
             }); var loss = client.onConnectionLost(losses::add)) {
            try {
                server.connect(client);
                server.send(blocking);
                awaitLatch(entered);
                server.send(event(ROOM_CLOSED, "Old queued event"));
                var pending = client.request(ping("old-request"));
                server.receive();
                server.peer.close();
                assertInstanceOf(EOFException.class, failureOf(pending));

                if (reconnect) {
                    replacement.connect(client);
                    var fresh = event(LOBBY_UPDATED, "New connection");
                    replacement.send(fresh);
                    assertEquals(fresh, take(otherEvents));
                } else {
                    client.close();
                }
                awaitLatch(interrupted);
                worker.get().join(5_000);
                assertFalse(worker.get().isAlive(), "The retired event worker should terminate");
                assertTrue(otherEvents.isEmpty());
                assertTrue(losses.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void timesOutRequestsAndIgnoresUnmatchedAndLateReplies() throws Exception {
        var events = new LinkedBlockingQueue<ProtocolMessage>();
        try (var client = new TcpQuizClient(Duration.ofSeconds(3), Duration.ofSeconds(1));
             var server = new LoopbackServer(); var subscription = client.subscribe(events::add)) {
            server.connect(client);
            var missed = client.request(ping("missed"));
            assertEquals("missed", server.receive().requestId());
            assertInstanceOf(TimeoutException.class, failureOf(missed));

            var next = client.request(ping("next"));
            assertEquals("next", server.receive().requestId());
            var marker = event(LOBBY_UPDATED, "Marker");
            server.write(MessageCodec.encodeLine(pong("missed")), MessageCodec.encodeLine(pong("unknown")),
                    MessageCodec.encodeLine(marker), MessageCodec.encodeLine(pong("next")));
            assertEquals(pong("next"), await(next));
            assertEquals(marker, take(events));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void boundsPendingRequestsAndReleasesSlotsAfterReplyAndCancellation() throws Exception {
        try (var client = new TcpQuizClient(Duration.ofSeconds(3), Duration.ofSeconds(10)); var server = new LoopbackServer()) {
            server.connect(client);
            List<CompletableFuture<ProtocolMessage>> requests = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                requests.add(client.request(ping("bound-" + i)));
                assertEquals("bound-" + i, server.receive().requestId());
            }
            assertInstanceOf(RejectedExecutionException.class, failureOf(client.request(ping("excess"))));
            assertTrue(requests.getFirst().cancel(false));
            server.send(pong("bound-1"));
            assertEquals(pong("bound-1"), await(requests.get(1)));

            var replacement = client.request(ping("replacement"));
            var another = client.request(ping("another"));
            assertEquals("replacement", server.receive().requestId());
            assertEquals("another", server.receive().requestId());
            server.send(pong("replacement"));
            server.send(pong("another"));
            assertEquals(pong("replacement"), await(replacement));
            assertEquals(pong("another"), await(another));
        }
    }

    @Test
    void eventBackpressureEndsTheSessionAndNotifiesLossAfterAcceptedEvents() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var notices = new LinkedBlockingQueue<Object>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var subscription = client.subscribe(message -> { entered.countDown(); awaitLatch(release); notices.add(message); });
             var loss = client.onConnectionLost(notices::add)) {
            server.connect(client);
            var pending = client.request(ping("backpressure"));
            server.receive();
            var message = event(LOBBY_UPDATED, "Busy");
            byte[] frame = MessageCodec.encodeLine(message);
            try {
                server.write(frame);
                awaitLatch(entered);
                byte[][] burst = new byte[257][];
                Arrays.fill(burst, frame);
                server.write(burst);
                assertEquals("Incoming TCP event queue is full", failureOf(pending).getMessage());
                assertFalse(client.isConnected());
            } finally {
                release.countDown();
            }
            for (int i = 0; i < 257; i++) {
                assertEquals(message, take(notices));
            }
            assertInstanceOf(IOException.class, take(notices));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "null", "{\"type\":\"LOBBY_UPDATED\"} {\"type\":\"POLL_STARTED\"}"})
    void malformedJsonDisconnectsAndFailsPendingRequests(String invalidJson) throws Exception {
        assertMalformedFrame((invalidJson + '\n').getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidUtf8IsRejected() throws Exception {
        byte[] frame = MessageCodec.encodeLine(event(POLL_STARTED, "é"));
        int invalid = new String(frame, StandardCharsets.UTF_8).indexOf('é') + 1;
        frame[invalid] = '('; // Start byte of a two-byte UTF-8 sequence, followed by a non-continuation byte.
        assertMalformedFrame(frame);
    }

    @Test
    void rejectsOverlongUnterminatedFrameAndAcceptsExactByteLimit() throws Exception {
        var events = new LinkedBlockingQueue<ProtocolMessage>();
        var losses = new LinkedBlockingQueue<IOException>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var subscription = client.subscribe(events::add); var loss = client.onConnectionLost(losses::add)) {
            server.connect(client);
            int overhead = MessageCodec.encodeLine(event(POLL_STARTED, "")).length - 1;
            int textBytes = MessageCodec.MAX_FRAME_BYTES - overhead;
            var maxFrame = event(POLL_STARTED, "é".repeat(textBytes / 2) + "x".repeat(textBytes % 2));
            assertEquals(MessageCodec.MAX_FRAME_BYTES + 1, MessageCodec.encodeLine(maxFrame).length);
            server.send(maxFrame);
            assertEquals(maxFrame, take(events));

            server.write("é".repeat(MessageCodec.MAX_FRAME_BYTES / 2 + 1).getBytes(StandardCharsets.UTF_8));
            assertTrue(take(losses).getMessage().contains("exceeds 16384 bytes"));
            assertFalse(client.isConnected());
        }
    }

    @Test
    void oversizedOutgoingRequestFailsWithoutDisconnecting() throws Exception {
        try (var client = new TcpQuizClient(); var server = new LoopbackServer()) {
            server.connect(client);
            var oversized = new ProtocolMessage(PING, "oversized", null, null, null,
                    Map.of("text", "x".repeat(MessageCodec.MAX_FRAME_BYTES)));
            assertInstanceOf(IllegalArgumentException.class, failureOf(client.request(oversized)));
            var request = client.request(ping("valid"));
            assertEquals("valid", server.receive().requestId());
            server.send(pong("valid"));
            assertEquals(pong("valid"), await(request));
        }
    }

    @Test
    void eofMidFrameDoesNotDeliverThePartialEvent() throws Exception {
        var notices = new LinkedBlockingQueue<Object>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer();
             var events = client.subscribe(notices::add); var loss = client.onConnectionLost(notices::add)) {
            server.connect(client);
            server.write("{\"type\":\"POLL_STARTED\"".getBytes(StandardCharsets.UTF_8));
            server.peer.close();
            var failure = assertInstanceOf(EOFException.class, take(notices));
            assertTrue(failure.getMessage().contains("middle of a JSON frame"));
        }
    }

    private static void assertMalformedFrame(byte[] frame) throws Exception {
        var losses = new LinkedBlockingQueue<IOException>();
        try (var client = new TcpQuizClient(); var server = new LoopbackServer(); var loss = client.onConnectionLost(losses::add)) {
            server.connect(client);
            var request = client.request(ping("waiting"));
            server.receive();
            server.write(frame);
            var failure = take(losses);
            assertTrue(failure.getMessage().contains("invalid UTF-8 JSON frame"));
            assertSame(failure, failureOf(request));
            assertFalse(client.isConnected());
        }
    }

    private static ProtocolMessage ping(String id) {
        return new ProtocolMessage(PING, id, null, null, null, Map.of());
    }

    private static ProtocolMessage pong(String id) {
        return new ProtocolMessage(PONG, id, null, "OK", null, Map.of());
    }

    private static ProtocolMessage event(String type, String text) {
        return new ProtocolMessage(type, null, "ABC123", null, null, Map.of("text", text));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> await(future)).getCause();
    }

    private static <T> T take(BlockingQueue<T> messages) throws InterruptedException {
        T message = messages.poll(5, SECONDS);
        assertNotNull(message, "Expected a notification");
        return message;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, SECONDS), "Timed out waiting for the test to release the latch");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    /** Test-only loopback peer. Its listening socket uses an ephemeral port. */
    private static final class LoopbackServer implements AutoCloseable {
        private final ServerSocket server;
        private Socket peer;
        private BufferedReader input;

        LoopbackServer() throws IOException {
            server = new ServerSocket(0, 1, LOOPBACK);
            server.setSoTimeout(5_000);
        }

        int port() { return server.getLocalPort(); }

        void connect(TcpQuizClient client) throws Exception {
            var connection = client.connect(HOST, port());
            accept();
            await(connection);
        }

        void accept() throws IOException {
            peer = server.accept();
            peer.setSoTimeout(5_000);
            input = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
        }

        ProtocolMessage receive() throws IOException {
            String line = input.readLine();
            assertNotNull(line, "Client closed the test connection");
            return MessageCodec.decodeJson(line.getBytes(StandardCharsets.UTF_8));
        }

        void send(ProtocolMessage message) throws IOException {
            write(MessageCodec.encodeLine(message));
        }

        void write(byte[]... frames) throws IOException {
            var output = peer.getOutputStream();
            var batch = new java.io.ByteArrayOutputStream();
            for (byte[] frame : frames) {
                batch.writeBytes(frame);
            }
            batch.writeTo(output);
            output.flush();
        }

        @Override
        public void close() throws IOException {
            try (server; Socket accepted = peer) {
                // Both sockets are closed even if a test fails before accepting a connection.
            }
        }
    }
}
