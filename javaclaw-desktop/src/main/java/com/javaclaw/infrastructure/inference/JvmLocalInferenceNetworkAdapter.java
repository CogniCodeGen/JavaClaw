package com.javaclaw.infrastructure.inference;

import com.javaclaw.application.inference.LocalInferenceNetworkPort;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Comparator;
import java.util.Optional;

/** 仅选择已启动网卡上的 RFC1918 IPv4；绝不回落到通配或公网地址。 */
public final class JvmLocalInferenceNetworkAdapter implements LocalInferenceNetworkPort {

    @Override
    public Optional<String> preferredPrivateIpv4() {
        Optional<String> routed = defaultRouteAddress();
        if (routed.isPresent()) return routed;
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(this::eligible)
                    .sorted(Comparator.comparingInt(NetworkInterface::getIndex))
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(JvmLocalInferenceNetworkAdapter::privateIpv4)
                    .map(InetAddress::getHostAddress)
                    .findFirst();
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    @Override
    public boolean portAvailable(String bindAddress, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private Optional<String> defaultRouteAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            // UDP connect only asks the OS for its preferred route; it sends no packet.
            socket.connect(InetAddress.getByName("192.0.2.1"), 9);
            InetAddress address = socket.getLocalAddress();
            return privateIpv4(address) ? Optional.of(address.getHostAddress()) : Optional.empty();
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    private boolean eligible(NetworkInterface value) {
        try {
            return value.isUp() && !value.isLoopback() && !value.isVirtual();
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static boolean privateIpv4(InetAddress value) {
        return value instanceof Inet4Address && !value.isLoopbackAddress()
                && value.isSiteLocalAddress();
    }
}
