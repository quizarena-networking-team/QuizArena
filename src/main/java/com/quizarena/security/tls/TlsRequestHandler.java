package com.quizarena.security.tls;

import com.quizarena.protocol.ProtocolMessage;

@FunctionalInterface
public interface TlsRequestHandler {
    void handle(ProtocolMessage request, TlsHostSession session) throws Exception;
}

