package com.quizarena.security.tls;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.AuthenticatedHost;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Optional;

/** State and reply operations exposed to the TLS command dispatcher. */
public interface TlsSessionContext {
    void send(ProtocolMessage message) throws IOException;
    Optional<AuthenticatedHost> authenticatedHost();
    boolean authenticate(AuthenticatedHost host);
    SocketAddress remoteAddress();
    String protocol();
}

