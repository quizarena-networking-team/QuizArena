package com.quizarena.udp.multicast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;

/** Chooses the network interface used to send and receive multicast announcements. */
public final class MulticastInterfaces {

    private MulticastInterfaces() {
    }

    /**
     * Returns the named interface if one is configured. Otherwise returns the first active,
     * multicast-capable, non-loopback interface with an IPv4 address, falling back to the
     * loopback interface for single-computer demos. Returns null if none qualifies.
     */
    public static NetworkInterface select(String configuredName) throws SocketException {
        if (configuredName != null && !configuredName.isBlank()) {
            NetworkInterface named = NetworkInterface.getByName(configuredName.strip());
            if (named == null) {
                throw new SocketException("No network interface named " + configuredName);
            }
            return named;
        }
        List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
        NetworkInterface loopback = null;
        for (NetworkInterface nif : all) {
            if (!nif.isUp() || !nif.supportsMulticast() || !hasIpv4(nif)) {
                continue;
            }
            if (nif.isLoopback()) {
                loopback = loopback == null ? nif : loopback;
            } else if (!nif.isVirtual() && !nif.isPointToPoint()) {
                return nif;
            }
        }
        return loopback;
    }

    private static boolean hasIpv4(NetworkInterface nif) {
        return Collections.list(nif.getInetAddresses()).stream().anyMatch(Inet4Address.class::isInstance);
    }
}
