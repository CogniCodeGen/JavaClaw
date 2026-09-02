package com.javaclaw.api;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 精确 HTTPS Origin 与 DNS 地址集合的临时私网授权快照。
 *
 * <p>地址必须是由服务端校验并规范化的数字地址；构造器不执行 DNS。撤销通过新 revision 写入 tombstone。
 *
 * @param id 稳定授权标识
 * @param revision 不可变版本，从 1 开始
 * @param state 生命周期
 * @param workspaceId 所属 Workspace
 * @param purpose MCP 或 Site 用途
 * @param origin 不含路径、凭据、查询和片段的精确 HTTPS Origin
 * @param dnsAddresses 用户确认时冻结的规范数字地址集合
 * @param expiresAt 到期时间
 * @param createdAt 首次创建时间
 * @param updatedAt 当前版本写入时间
 */
public record PrivateNetworkGrant(
        String id,
        long revision,
        SecurityGrantState state,
        WorkspaceId workspaceId,
        PrivateNetworkPurpose purpose,
        URI origin,
        Set<String> dnsAddresses,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
    /** 复制集合并校验稳定身份、Origin 与时间。 */
    public PrivateNetworkGrant {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(purpose, "purpose");
        origin = normalizeOrigin(origin);
        dnsAddresses = Set.copyOf(dnsAddresses);
        if (dnsAddresses.isEmpty()) {
            throw new IllegalArgumentException("dnsAddresses must not be empty");
        }
        dnsAddresses.forEach(address -> Preconditions.text(address, "dnsAddress"));
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requireConsistentTimes(createdAt, updatedAt, expiresAt);
    }

    /**
     * 规范化为可精确比较的 HTTPS Origin。
     *
     * @param value 输入 URI
     * @return 小写 ASCII host、无路径且默认端口省略的 URI
     */
    public static URI normalizeOrigin(URI value) {
        URI origin = Objects.requireNonNull(value, "origin");
        requireOriginShape(origin);
        String host = normalizedHost(origin.getHost());
        int port = normalizedPort(origin.getPort());
        try {
            return new URI("https", null, host, port, null, null, null);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("origin host is invalid", failure);
        }
    }

    private static void requireOriginShape(URI origin) {
        String path = origin.getPath();
        boolean pathIsOrigin = path == null || path.isEmpty() || "/".equals(path);
        if (!origin.isAbsolute()
                || !"https".equalsIgnoreCase(origin.getScheme())
                || origin.getHost() == null
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || !pathIsOrigin) {
            throw new IllegalArgumentException("origin must be an exact HTTPS origin");
        }
    }

    private static int normalizedPort(int port) {
        if (port == 443) {
            return -1;
        }
        if (port < -1 || port == 0 || port > 65_535) {
            throw new IllegalArgumentException("origin port is invalid");
        }
        return port;
    }

    private static String normalizedHost(String host) {
        if (host.indexOf(':') >= 0) {
            if (host.indexOf('%') >= 0) {
                throw new IllegalArgumentException("origin IPv6 scope is not allowed");
            }
            return host.toLowerCase(Locale.ROOT);
        }
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("origin host is invalid", failure);
        }
    }

    private static void requireConsistentTimes(Instant createdAt, Instant updatedAt, Instant expiresAt) {
        if (!createdAt.isBefore(expiresAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("grant timestamps are inconsistent");
        }
    }
}
