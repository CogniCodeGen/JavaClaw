package com.javaclaw.server.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicNetworkAddressTest {
    @Test
    void allowsPublicIpv4AndRejectsPrivateReservedAndDocumentationRanges() throws Exception {
        assertTrue(allowed("8.8.8.8"));
        assertTrue(allowed("1.1.1.1"));
        assertFalse(allowed("0.1.2.3"));
        assertFalse(allowed("10.0.0.1"));
        assertFalse(allowed("100.64.0.1"));
        assertFalse(allowed("127.0.0.1"));
        assertFalse(allowed("169.254.1.1"));
        assertFalse(allowed("172.31.255.255"));
        assertFalse(allowed("192.0.2.1"));
        assertFalse(allowed("192.168.1.1"));
        assertFalse(allowed("198.18.0.1"));
        assertFalse(allowed("198.51.100.1"));
        assertFalse(allowed("203.0.113.1"));
        assertFalse(allowed("224.0.0.1"));
    }

    @Test
    void checksEveryIpv4ReservationBoundaryWithoutBroadRangeAssumptions() throws Exception {
        assertTrue(allowed("100.63.255.255"));
        assertFalse(allowed("100.127.255.255"));
        assertTrue(allowed("100.128.0.1"));
        assertTrue(allowed("169.253.255.255"));
        assertTrue(allowed("169.255.0.1"));
        assertTrue(allowed("172.15.255.255"));
        assertFalse(allowed("172.16.0.1"));
        assertTrue(allowed("172.32.0.1"));
        assertFalse(allowed("192.0.0.1"));
        assertTrue(allowed("192.0.1.1"));
        assertTrue(allowed("192.0.3.1"));
        assertFalse(allowed("198.19.255.255"));
        assertTrue(allowed("198.20.0.1"));
        assertTrue(allowed("198.51.99.1"));
        assertTrue(allowed("198.51.101.1"));
        assertTrue(allowed("203.0.112.1"));
        assertTrue(allowed("203.0.114.1"));
    }

    @Test
    void allowsGlobalIpv6AndRejectsLocalDocumentationAndEmbeddedPrivateAddresses() throws Exception {
        assertTrue(allowed("2001:4860:4860::8888"));
        assertTrue(allowed("2002:0808:0808::"));
        assertFalse(allowed("::"));
        assertFalse(allowed("::1"));
        assertFalse(allowed("fc00::1"));
        assertFalse(allowed("fe80::1"));
        assertFalse(allowed("2001:db8::1"));
        assertFalse(allowed("2002:0a00:0001::"));
        assertFalse(allowed("64:ff9b::0a00:0001"));
    }

    @Test
    void validatesMappedNat64AndSixToFourAddressesByEmbeddedIpv4() throws Exception {
        assertTrue(PublicNetworkAddress.isAllowed(
                embedded(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1}, 8, 8, 8, 8)));
        assertFalse(PublicNetworkAddress.isAllowed(
                embedded(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1}, 10, 0, 0, 1)));
        assertTrue(PublicNetworkAddress.isAllowed(
                embedded(new byte[] {0, 0x64, -1, -101, 0, 0, 0, 0, 0, 0, 0, 0}, 8, 8, 4, 4)));
        assertFalse(PublicNetworkAddress.isAllowed(
                embedded(new byte[] {0, 0x64, -1, -101, 0, 0, 0, 0, 0, 0, 0, 0}, 10, 0, 0, 1)));
        assertFalse(PublicNetworkAddress.isAllowed(
                InetAddress.getByAddress(new byte[] {0, 0x64, -1, -101, 0, 1, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8})));
        assertTrue(PublicNetworkAddress.isAllowed(embedded(new byte[] {0x20, 0x02}, 8, 8, 8, 8, 2)));
        assertFalse(PublicNetworkAddress.isAllowed(embedded(new byte[] {0x20, 0x02}, 10, 0, 0, 1, 2)));
        assertFalse(PublicNetworkAddress.isAllowed(
                InetAddress.getByAddress(new byte[] {0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertThrows(NullPointerException.class, () -> PublicNetworkAddress.isAllowed(null));
    }

    @Test
    void validatesMappedIpv4BeforeInetAddressCanCollapseItsRepresentation() throws Exception {
        assertTrue(PublicNetworkAddress.isAllowed(
                ipv6(embeddedBytes(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1}, 8, 8, 8, 8, 12))));
        assertFalse(PublicNetworkAddress.isAllowed(
                ipv6(embeddedBytes(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1}, 10, 0, 0, 1, 12))));
        assertFalse(PublicNetworkAddress.isAllowed(ipv6(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 8, 8, 8, 8})));
    }

    @Test
    void exercisesIpv6PrefixNearMissesAndIpv4ShortCircuitBoundaries() throws Exception {
        assertFalse(PublicNetworkAddress.isAllowed(
                ipv6(new byte[] {0, 0x64, -1, -101, 0, 0, 0, 0, 0, 1, 0, 0, 8, 8, 8, 8})));
        assertFalse(PublicNetworkAddress.isAllowed(
                ipv6(new byte[] {0, 0x64, -1, -101, 0, 1, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8})));
        assertTrue(PublicNetworkAddress.isAllowed(
                ipv6(new byte[] {0x20, 0x01, 0x0d, -73, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})));
        assertTrue(allowed("11.0.0.1"));
        assertTrue(allowed("128.0.0.1"));
        assertFalse(allowed("169.254.0.0"));
        assertFalse(allowed("172.31.0.1"));
        assertFalse(allowed("192.0.0.2"));
        assertTrue(allowed("192.1.0.1"));
        assertFalse(allowed("198.51.100.254"));
        assertTrue(allowed("203.1.0.1"));
        assertTrue(
                PublicNetworkAddress.isAllowed(ipv6(new byte[] {0x20, 1, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1})));
        assertTrue(
                PublicNetworkAddress.isAllowed(ipv6(new byte[] {0x20, 1, 0, 0, 0, 0, 0, 0, 0, 0, -1, 1, 0, 0, 0, 1})));
        assertFalse(PublicNetworkAddress.isAllowed(
                ipv6(new byte[] {0, 0x65, -1, -101, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8})));
        assertFalse(PublicNetworkAddress.isAllowed(
                ipv6(new byte[] {0, 0x64, -2, -101, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8})));
        assertFalse(PublicNetworkAddress.isAllowed(
                ipv6(new byte[] {0, 0x64, -1, -100, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8})));
        assertFalse(PublicNetworkAddress.isAllowed(
                ipv6(new byte[] {0, 0x64, -1, -101, 1, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8})));
    }

    @Test
    void privateGrantEligibilityAcceptsOnlyRfc1918AndUlaRanges() throws Exception {
        assertTrue(grantable("10.0.0.1"));
        assertTrue(grantable("172.16.0.1"));
        assertTrue(grantable("172.31.255.255"));
        assertTrue(grantable("192.168.1.1"));
        assertFalse(grantable("172.15.255.255"));
        assertFalse(grantable("172.32.0.1"));
        assertFalse(grantable("192.167.1.1"));
        assertFalse(grantable("192.169.1.1"));
        assertFalse(grantable("127.0.0.1"));
        assertFalse(grantable("8.8.8.8"));
        assertTrue(grantable("fc00::1"));
        assertTrue(grantable("fdff::1"));
        assertFalse(grantable("fe80::1"));
        assertFalse(PublicNetworkAddress.isGrantablePrivate(
                ipv6(embeddedBytes(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1}, 10, 0, 0, 1, 12))));
        assertThrows(NullPointerException.class, () -> PublicNetworkAddress.isGrantablePrivate(null));
    }

    private static boolean allowed(String address) throws Exception {
        return PublicNetworkAddress.isAllowed(InetAddress.getByName(address));
    }

    private static boolean grantable(String address) throws Exception {
        return PublicNetworkAddress.isGrantablePrivate(InetAddress.getByName(address));
    }

    private static InetAddress embedded(byte[] prefix, int first, int second, int third, int fourth) throws Exception {
        return embedded(prefix, first, second, third, fourth, 12);
    }

    private static InetAddress embedded(byte[] prefix, int first, int second, int third, int fourth, int offset)
            throws Exception {
        return InetAddress.getByAddress(embeddedBytes(prefix, first, second, third, fourth, offset));
    }

    private static byte[] embeddedBytes(byte[] prefix, int first, int second, int third, int fourth, int offset) {
        byte[] address = Arrays.copyOf(prefix, 16);
        address[offset] = (byte) first;
        address[offset + 1] = (byte) second;
        address[offset + 2] = (byte) third;
        address[offset + 3] = (byte) fourth;
        return address;
    }

    private static InetAddress ipv6(byte[] bytes) throws Exception {
        return Inet6Address.getByAddress(null, bytes, -1);
    }
}
