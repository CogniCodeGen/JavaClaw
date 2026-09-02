package com.javaclaw.server.security.grant;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 不执行 DNS 的私网地址规范化与永久拒绝策略。 */
final class PrivateNetworkAddressPolicy {
    private static final byte[] AMAZON_METADATA = ipv4(169, 254, 169, 254);
    private static final byte[] ALIBABA_METADATA = ipv4(100, 100, 100, 200);
    private static final byte[] AZURE_METADATA = ipv4(168, 63, 129, 16);
    private static final byte[] AMAZON_IPV6_METADATA = parseIpv6("fd00:ec2::254");

    private PrivateNetworkAddressPolicy() {}

    static Set<String> requireGrantable(Set<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("dnsAddresses must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        boolean containsPrivate = false;
        for (String value : addresses) {
            byte[] address = parse(value);
            if (isPermanentlyForbidden(address)) {
                throw new SecurityException("loopback、link-local、metadata、multicast 与未指定地址永不可授权");
            }
            containsPrivate |= isPrivate(address);
            normalized.add(format(address));
        }
        if (!containsPrivate) {
            throw new IllegalArgumentException("private network grant must contain an RFC1918 or ULA address");
        }
        return Set.copyOf(normalized);
    }

    private static byte[] parse(String value) {
        String address = java.util.Objects.requireNonNull(value, "dnsAddress").strip();
        if (address.indexOf('%') >= 0 || address.isEmpty()) {
            throw new IllegalArgumentException("scoped or empty address is not allowed");
        }
        return address.indexOf(':') >= 0 ? parseIpv6(address) : parseIpv4(address);
    }

    private static byte[] parseIpv4(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("IPv4 address must contain four decimal octets");
        }
        byte[] bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (!part.matches("0|[1-9][0-9]{0,2}")) {
                throw new IllegalArgumentException("IPv4 octet is not canonical decimal");
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                throw new IllegalArgumentException("IPv4 octet exceeds 255");
            }
            bytes[index] = (byte) octet;
        }
        return bytes;
    }

    private static byte[] parseIpv6(String address) {
        if (!address.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("IPv6 address contains unsupported characters");
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            if (!(parsed instanceof Inet6Address)) {
                throw new IllegalArgumentException("address is not IPv6");
            }
            return parsed.getAddress();
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("IPv6 address is invalid", failure);
        }
    }

    private static boolean isPermanentlyForbidden(byte[] address) {
        try {
            InetAddress parsed = InetAddress.getByAddress(address);
            return parsed.isAnyLocalAddress()
                    || parsed.isLoopbackAddress()
                    || parsed.isLinkLocalAddress()
                    || parsed.isMulticastAddress()
                    || Arrays.equals(address, AMAZON_METADATA)
                    || Arrays.equals(address, ALIBABA_METADATA)
                    || Arrays.equals(address, AZURE_METADATA)
                    || Arrays.equals(address, AMAZON_IPV6_METADATA);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("validated address length became invalid", impossible);
        }
    }

    private static boolean isPrivate(byte[] address) {
        if (address.length == 4) {
            int first = Byte.toUnsignedInt(address[0]);
            int second = Byte.toUnsignedInt(address[1]);
            return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
        }
        return (Byte.toUnsignedInt(address[0]) & 0xfe) == 0xfc;
    }

    private static String format(byte[] address) {
        if (address.length == 4) {
            return Byte.toUnsignedInt(address[0])
                    + "."
                    + Byte.toUnsignedInt(address[1])
                    + "."
                    + Byte.toUnsignedInt(address[2])
                    + "."
                    + Byte.toUnsignedInt(address[3]);
        }
        try {
            return InetAddress.getByAddress(address).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("validated address length became invalid", impossible);
        }
    }

    private static byte[] ipv4(int first, int second, int third, int fourth) {
        return new byte[] {(byte) first, (byte) second, (byte) third, (byte) fourth};
    }
}
