package com.javaclaw.api;

import java.util.Locale;
import java.util.Set;

/**
 * Network Broker 的允许列表。
 *
 * @param hosts 小写 DNS 主机名；{@value #ANY_HOST} 表示任意主机，空集合表示禁止联网
 * @param ports 允许端口；{@value #ANY_PORT} 只允许可信系统上限使用，执行前必须收窄
 * @param tlsOnly 是否只允许 TLS
 */
public record NetworkPermission(Set<String> hosts, Set<Integer> ports, boolean tlsOnly) {
    /** 权限上限可使用的“任意主机”标记；最终执行仍应由更窄配置或资源白名单收窄。 */
    public static final String ANY_HOST = "*";

    /** 权限上限可使用的“任意端口”标记；用户配置和网络执行必须拒绝未收窄值。 */
    public static final int ANY_PORT = 0;

    /** 规范化主机名并校验端口。 */
    public NetworkPermission {
        hosts = hosts.stream()
                .map(value -> Preconditions.text(value, "host").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ports = Set.copyOf(ports);
        if (ports.stream().anyMatch(port -> port == null || port < ANY_PORT || port > 65_535)) {
            throw new IllegalArgumentException("ports must be ANY_PORT or between 1 and 65535");
        }
    }

    /**
     * 判断精确主机是否在当前配置中。
     *
     * @param host DNS 主机名
     * @return 明确列出或配置为任意主机时为 true
     */
    public boolean allowsHost(String host) {
        String normalized = Preconditions.text(host, "host").toLowerCase(Locale.ROOT);
        return hosts.contains(ANY_HOST) || hosts.contains(normalized);
    }

    /**
     * 判断精确端口是否在当前配置中。
     *
     * @param port TCP 端口，取值 1..65535
     * @return 明确列出或系统上限配置为任意端口时为 true
     */
    public boolean allowsPort(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return ports.contains(ANY_PORT) || ports.contains(port);
    }
}
