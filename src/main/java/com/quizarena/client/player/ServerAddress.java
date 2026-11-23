package com.quizarena.client.player;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;

record ServerAddress(String host, int port) {
    static ServerAddress parse(String enteredHost, String enteredPort) {
        String host = enteredHost.strip();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Enter the server's hostname or IP address.");
        }

        String candidate;
        try {
            candidate = host.contains(":") ? bracketed(host) : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            var uri = new URI("tcp://" + candidate);
            if (!candidate.equals(uri.getHost()) || !candidate.equals(uri.getRawAuthority())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | URISyntaxException failure) {
            throw new IllegalArgumentException("Enter a valid hostname or IP address without a scheme or port.");
        }

        int port;
        try {
            port = Integer.parseInt(enteredPort.strip());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Enter a TCP port from 1 to 65535.");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Enter a TCP port from 1 to 65535.");
        }
        return new ServerAddress(candidate.startsWith("[") ? candidate.substring(1, candidate.length() - 1) : candidate, port);
    }

    private static String bracketed(String host) {
        return host.startsWith("[") ? host : "[" + host + "]";
    }
}
