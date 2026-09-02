package com.javaclaw.server.security;

import java.net.InetAddress;
import java.util.Objects;

/** 只接受可公开路由的目标地址，阻断 loopback、私网、链路本地、保留段和地址嵌套绕过。 */
final class PublicNetworkAddress {
    private PublicNetworkAddress() {}

    static boolean isAllowed(InetAddress address) {
        InetAddress checked = Objects.requireNonNull(address, "address");
        byte[] bytes = checked.getAddress();
        // InetAddress 在 JDK 中是密封层次，唯一地址表示为 4 字节 IPv4 或 16 字节 IPv6。
        return bytes.length == 4 ? publicIpv4(bytes) : publicIpv6(bytes);
    }

    /**
     * 判断地址是否只属于可被显式临时授权的 RFC1918 或 ULA 范围。
     *
     * <p>该方法不表示已授权；只用于在调用持久化授权回调前实施不可放宽的系统 ceiling。
     *
     * @param address DNS 返回的数字地址
     * @return 仅 RFC1918 或 ULA 时为 {@code true}
     */
    static boolean isGrantablePrivate(InetAddress address) {
        byte[] bytes = Objects.requireNonNull(address, "address").getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
        }
        return !ipv4Mapped(bytes) && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
    }

    private static boolean publicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        return !reservedFirstOctet(first)
                && !sharedAddressSpace(first, second)
                && !privateOrLinkLocal(first, second)
                && !protocolOrDocumentation(first, second, third)
                && !benchmarkOrDocumentation(first, second, third);
    }

    private static boolean reservedFirstOctet(int first) {
        return first == 0 || first == 10 || first == 127 || first >= 224;
    }

    private static boolean sharedAddressSpace(int first, int second) {
        return first == 100 && second >= 64 && second <= 127;
    }

    private static boolean privateOrLinkLocal(int first, int second) {
        return first == 169 && second == 254 || first == 172 && second >= 16 && second <= 31;
    }

    private static boolean protocolOrDocumentation(int first, int second, int third) {
        return first == 192 && (second == 168 || second == 0 && (third == 0 || third == 2));
    }

    private static boolean benchmarkOrDocumentation(int first, int second, int third) {
        return first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)
                || first == 203 && second == 0 && third == 113;
    }

    private static boolean publicIpv6(byte[] bytes) {
        if (ipv4Mapped(bytes)) {
            return publicIpv4(java.util.Arrays.copyOfRange(bytes, 12, 16));
        }
        if (wellKnownNat64(bytes)) {
            return publicIpv4(java.util.Arrays.copyOfRange(bytes, 12, 16));
        }
        if ((Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc || localNat64(bytes) || documentation(bytes)) {
            return false;
        }
        if (sixToFour(bytes)) {
            return publicIpv4(java.util.Arrays.copyOfRange(bytes, 2, 6));
        }
        return (Byte.toUnsignedInt(bytes[0]) & 0xe0) == 0x20;
    }

    private static boolean ipv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean wellKnownNat64(byte[] bytes) {
        return bytes[0] == 0x00
                && bytes[1] == 0x64
                && bytes[2] == (byte) 0xff
                && bytes[3] == (byte) 0x9b
                && zero(bytes, 4, 12);
    }

    private static boolean localNat64(byte[] bytes) {
        return bytes[0] == 0x00
                && bytes[1] == 0x64
                && bytes[2] == (byte) 0xff
                && bytes[3] == (byte) 0x9b
                && bytes[4] == 0x00
                && bytes[5] == 0x01;
    }

    private static boolean documentation(byte[] bytes) {
        return bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8;
    }

    private static boolean sixToFour(byte[] bytes) {
        return bytes[0] == 0x20 && bytes[1] == 0x02;
    }

    private static boolean zero(byte[] bytes, int start, int end) {
        for (int index = start; index < end; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }
}
