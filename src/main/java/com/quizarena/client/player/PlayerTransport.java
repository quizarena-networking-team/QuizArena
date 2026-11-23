package com.quizarena.client.player;

import com.quizarena.client.network.TcpQuizClient;
import com.quizarena.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The transport operations needed by one player session. */
interface PlayerTransport extends AutoCloseable {
    CompletableFuture<Void> connect(String host, int port);
    boolean isConnected();
    CompletableFuture<ProtocolMessage> request(ProtocolMessage request);
    AutoCloseable subscribe(Consumer<ProtocolMessage> listener);
    AutoCloseable onConnectionLost(Consumer<IOException> listener);
    void disconnect();
    @Override void close();

    record Tcp(TcpQuizClient client) implements PlayerTransport {
        @Override public CompletableFuture<Void> connect(String host, int port) { return client.connect(host, port); }
        @Override public boolean isConnected() { return client.isConnected(); }
        @Override public CompletableFuture<ProtocolMessage> request(ProtocolMessage request) { return client.request(request); }
        @Override public AutoCloseable subscribe(Consumer<ProtocolMessage> listener) { return client.subscribe(listener); }
        @Override public AutoCloseable onConnectionLost(Consumer<IOException> listener) { return client.onConnectionLost(listener); }
        @Override public void disconnect() { client.disconnect(); }
        @Override public void close() { client.close(); }
    }
}
