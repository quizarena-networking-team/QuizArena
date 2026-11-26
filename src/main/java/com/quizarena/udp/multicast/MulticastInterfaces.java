package com.quizarena.udp.multicast;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chooses network interfaces for multicast announcements.
 *
 * Group membership is per interface: a listener only receives datagrams that arrive on an
 * interface it joined. The sender therefore uses the interface that reaches the LAN, and
 * listeners join every usable interface, so delivery does not depend on both sides guessing
 * the same adapter (VPN, Hyper-V and VirtualBox adapters make that guess unreliable).
 */
public final class MulticastInterfaces {

    /** Any routable address works; connecting a UDP socket sends nothing, it only picks a route. */
    private static final String ROUTE_PROBE = "8.8.8.8";

    private MulticastInterfaces() {
    }

    /**
     * The interface to send announcements on: the configured one, otherwise the interface holding
     * the default route (normally the Wi-Fi/Ethernet adapter on the shared LAN), otherwise the
     * first usable non-loopback interface, otherwise loopback. Null if none is usable.
     */
    public static NetworkInterface forSending(String configuredName) throws SocketException {
        if (isConfigured(configuredName)) {
            return named(configuredName);
        }
        NetworkInterface routed = defaultRouteInterface();
        if (routed != null && usable(routed) && !routed.isLoopback() && !routed.isPointToPoint()) {
            return routed;
        }
        List<NetworkInterface> candidates = forReceiving(null);
        return candidates.stream().filter(nif -> !isLoopback(nif)).findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
    }

    /**
     * The interfaces a listener joins: only the configured one, otherwise every active,
     * multicast-capable interface with an IPv4 address (including loopback).
     */
    public static List<NetworkInterface> forReceiving(String configuredName) throws SocketException {
        if (isConfigured(configuredName)) {
            return List.of(named(configuredName));
        }
        List<NetworkInterface> usable = new ArrayList<>();
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (usable(nif)) {
                usable.add(nif);
            }
        }
        return List.copyOf(usable);
    }

    private static NetworkInterface defaultRouteInterface() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(ROUTE_PROBE), 9);
            InetAddress local = probe.getLocalAddress();
            return local == null || local.isAnyLocalAddress() ? null : NetworkInterface.getByInetAddress(local);
        } catch (IOException | RuntimeException noRoute) {
            return null; // offline or no default gateway: fall back to the interface list
        }
    }

    private static boolean usable(NetworkInterface nif) throws SocketException {
        return nif.isUp() && nif.supportsMulticast()
                && Collections.list(nif.getInetAddresses()).stream().anyMatch(Inet4Address.class::isInstance);
    }

    private static boolean isLoopback(NetworkInterface nif) {
        try {
            return nif.isLoopback();
        } catch (SocketException failure) {
            return false;
        }
    }

    private static boolean isConfigured(String configuredName) {
        return configuredName != null && !configuredName.isBlank();
    }

    private static NetworkInterface named(String configuredName) throws SocketException {
        NetworkInterface named = NetworkInterface.getByName(configuredName.strip());
        if (named == null) {
            throw new SocketException("No network interface named " + configuredName);
        }
        return named;
    }
}
