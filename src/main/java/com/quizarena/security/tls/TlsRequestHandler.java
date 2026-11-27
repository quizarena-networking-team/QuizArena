package com.quizarena.security.tls;

import com.quizarena.protocol.ProtocolMessage;

@FunctionalInterface
public interface TlsRequestHandler {
    void handle(ProtocolMessage request, TlsSessionContext session) throws Exception;
}
