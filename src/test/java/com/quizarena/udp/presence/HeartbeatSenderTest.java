package com.quizarena.udp.presence;

import com.quizarena.udp.DatagramCodec;
import com.quizarena.udp.UdpMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class HeartbeatSenderTest {

    @Test
    void sendsPeriodicHeartbeatsUntilClosed() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            InetSocketAddress target = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());

            try (HeartbeatSender ignored = HeartbeatSender.start(target, "ABC123", "p-101", Duration.ofMillis(100))) {
                for (int i = 0; i < 3; i++) {
                    assertEquals(UdpMessage.heartbeat("ABC123", "p-101"), receive(server));
                }
            }

            // Drain anything already in flight, then expect silence.
            server.setSoTimeout(300);
            try {
                while (true) {
                    receive(server);
                }
            } catch (SocketTimeoutException expected) {
                // no more heartbeats after close
            }
            assertThrows(SocketTimeoutException.class, () -> receive(server));
        }
    }

    @Test
    void rejectsMissingIdentity() {
        InetSocketAddress target = new InetSocketAddress(InetAddress.getLoopbackAddress(), 5002);
        assertThrows(IllegalArgumentException.class,
                () -> HeartbeatSender.start(target, " ", "p-1", Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> HeartbeatSender.start(target, "ABC123", null, Duration.ofSeconds(2)));
    }

    private static UdpMessage receive(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[DatagramCodec.MAX_DATAGRAM_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return DatagramCodec.decode(packet.getData(), packet.getOffset(), packet.getLength()).orElseThrow();
    }
}
