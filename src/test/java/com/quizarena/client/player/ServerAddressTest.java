package com.quizarena.client.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerAddressTest {
    @Test
    void parsesHostnamesIpv4AndIpv6WithoutResolvingThem() {
        assertEquals(new ServerAddress("localhost", 5000), ServerAddress.parse(" localhost ", " 5000 "));
        assertEquals(new ServerAddress("192.168.1.20", 1), ServerAddress.parse("192.168.1.20", "1"));
        assertEquals(new ServerAddress("::1", 65_535), ServerAddress.parse("::1", "65535"));
        assertEquals(new ServerAddress("::1", 5000), ServerAddress.parse("[::1]", "5000"));
        assertEquals(new ServerAddress("xn--bcher-kva.example", 5000), ServerAddress.parse("bücher.example", "5000"));
    }

    @Test
    void rejectsMissingOrInvalidAddresses() {
        for (String host : List.of("", " ", "http://localhost", "localhost:5000", "127.0.0.1/path",
                "999.0.0.1", "host name", "[::1]:5000", "2001:::1")) {
            assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse(host, "5000"), host);
        }
    }

    @Test
    void rejectsInvalidPorts() {
        for (String port : List.of("", "https", "0", "-1", "65536", "4294967296")) {
            assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("localhost", port), port);
        }
    }
}
