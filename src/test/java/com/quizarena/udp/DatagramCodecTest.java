package com.quizarena.udp;

import com.quizarena.protocol.MessageTypes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatagramCodecTest {

    @Test
    void encodesTheProtocolDiscoveryRequestExactly() {
        assertEquals("{\"type\":\"DISCOVER\",\"version\":1}",
                new String(DatagramCodec.encode(UdpMessage.discover()), StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsEveryUdpMessageType() {
        for (UdpMessage message : new UdpMessage[]{
                UdpMessage.discover(),
                UdpMessage.discoverResult("QuizArena Server", 5000, 8443),
                UdpMessage.heartbeat("ABC123", "p-101"),
                UdpMessage.announcement("ABC123", "Round starts now")}) {
            assertEquals(Optional.of(message), decode(DatagramCodec.encode(message)));
        }
    }

    @Test
    void decodesTheExamplesFromTheSharedProtocol() {
        UdpMessage result = decode(utf8("{\"type\":\"DISCOVER_RESULT\",\"version\":1,"
                + "\"serverName\":\"QuizArena Server\",\"tcpPort\":5000,\"tlsPort\":8443}")).orElseThrow();
        assertTrue(result.is(MessageTypes.DISCOVER_RESULT));
        assertEquals(5000, result.tcpPort());
        assertEquals(8443, result.tlsPort());
    }

    @Test
    void ignoresUnknownFields() {
        assertEquals(Optional.of(UdpMessage.heartbeat("ABC123", "p-1")),
                decode(utf8("{\"type\":\"HEARTBEAT\",\"version\":1,\"roomCode\":\"ABC123\","
                        + "\"playerId\":\"p-1\",\"extra\":{\"x\":1}}")));
    }

    @Test
    void dropsMalformedOrUnsupportedDatagrams() {
        for (String bad : new String[]{
                "", "not json", "{\"type\":\"DISCOVER\"", "[1,2]", "null", "42",
                "{\"version\":1}",
                "{\"type\":\"\",\"version\":1}",
                "{\"type\":\"DISCOVER\"}",
                "{\"type\":\"DISCOVER\",\"version\":2}",
                "{\"type\":\"DISCOVER\",\"version\":\"one\"}"}) {
            assertFalse(decode(utf8(bad)).isPresent(), bad);
        }
    }

    @Test
    void rejectsDatagramsOverTheSizeLimit() {
        String big = "x".repeat(DatagramCodec.MAX_DATAGRAM_BYTES);
        assertThrows(IllegalArgumentException.class,
                () -> DatagramCodec.encode(UdpMessage.announcement("ABC123", big)));

        byte[] oversized = utf8("{\"type\":\"ANNOUNCEMENT\",\"version\":1,\"message\":\"" + big + "\"}");
        assertFalse(decode(oversized).isPresent());
    }

    @Test
    void decodesOnlyTheReceivedSliceOfABuffer() {
        byte[] body = DatagramCodec.encode(UdpMessage.discover());
        byte[] buffer = new byte[DatagramCodec.MAX_DATAGRAM_BYTES];
        System.arraycopy(body, 0, buffer, 10, body.length);
        assertEquals(Optional.of(UdpMessage.discover()), DatagramCodec.decode(buffer, 10, body.length));
    }

    @Test
    void readsConfigurationFromTheEnvironment() {
        UdpConfig config = UdpConfig.fromEnv(Map.of(
                "QUIZ_DISCOVERY_PORT", "6001",
                "QUIZ_MULTICAST_GROUP", "239.1.2.3",
                "QUIZ_MULTICAST_INTERFACE", " eth0 "));
        assertEquals(6001, config.discoveryPort());
        assertEquals(5002, config.heartbeatPort());
        assertEquals("239.1.2.3", config.multicastGroup());
        assertEquals("eth0", config.multicastInterface());
        assertThrows(IllegalArgumentException.class,
                () -> UdpConfig.fromEnv(Map.of("QUIZ_MULTICAST_PORT", "abc")));
    }

    private static Optional<UdpMessage> decode(byte[] data) {
        return DatagramCodec.decode(data, 0, data.length);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
