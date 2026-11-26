package com.quizarena.security.tls;

import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.security.AuthenticatedHost;

@FunctionalInterface
public interface AuthenticatedTlsCommandHandler {
    void handle(ProtocolMessage request, TlsSessionContext session, AuthenticatedHost host) throws Exception;
}
