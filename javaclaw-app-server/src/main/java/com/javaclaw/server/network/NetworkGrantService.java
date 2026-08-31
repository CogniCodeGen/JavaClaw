package com.javaclaw.server.network;

import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.javaclaw.agent.runtime.persistence.WorkspaceRepository;
import com.javaclaw.sandbox.api.NetworkBroker;

/** 精确端点、用途、Workspace 和 IP 共同授权；私网授权永远不能开放云元数据、链路本地或应用控制通道。 */
public final class NetworkGrantService implements NetworkGrantUseCases {
    private final NetworkGrantRepository repository;
    private final WorkspaceRepository workspaces;

    /** 复用唯一工作区权威与 H2 授权库。 */
    public NetworkGrantService(NetworkGrantRepository repository, WorkspaceRepository workspaces) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.workspaces = java.util.Objects.requireNonNull(workspaces);
    }

    /** 查询准确工作区的授权；不存在的工作区拒绝查询。 */
    public List<NetworkGrant> list(String workspaceId) {
        workspace(workspaceId);
        return repository.list(workspaceId);
    }

    /** 显式确认后写入授权；不接受通配符、地址段、HTTP 凭据或超过三十天的授权。 */
    public NetworkGrant put(NetworkGrant request, long expectedRevision, boolean confirmed, String key) {
        if (!confirmed) {
            throw new IllegalArgumentException("explicit endpoint confirmation is required");
        }
        workspace(request.workspaceId());
        if (request.origin().getRawQuery() != null
                || request.origin().getRawFragment() != null
                || (request.origin().getPath() != null
                        && !Set.of("", "/").contains(request.origin().getPath()))) {
            throw new IllegalArgumentException("grant must name an exact origin without a path");
        }
        if (!Set.of("WEB", "BROWSER", "MCP", "OAUTH").contains(request.purpose())) {
            throw new IllegalArgumentException("unsupported network purpose");
        }
        URI origin = origin(request.origin());
        Instant now = Instant.now();
        if (request.expiresAt() == null
                || !request.expiresAt().isAfter(now)
                || request.expiresAt().isAfter(now.plus(30, ChronoUnit.DAYS))
                || request.addresses().isEmpty()
                || request.addresses().size() > 16) {
            throw new IllegalArgumentException("private endpoint requires 1-16 exact addresses and a finite expiry");
        }
        var addresses = new java.util.LinkedHashSet<String>();
        for (String address : request.addresses()) {
            try {
                if (!address.matches("[0-9.]+|[0-9a-fA-F:]+") || address.contains("%")) {
                    throw new IllegalArgumentException("an exact IP literal is required");
                }
                InetAddress parsed = InetAddress.getByName(address);
                if (!grantable(parsed)) {
                    throw new IllegalArgumentException("control and metadata addresses cannot be granted");
                }
                addresses.add(parsed.getHostAddress());
            } catch (java.net.UnknownHostException invalid) {
                throw new IllegalArgumentException("invalid private IP literal", invalid);
            }
        }
        return repository.put(
                new NetworkGrant(
                        request.id(),
                        request.workspaceId(),
                        request.purpose(),
                        origin,
                        addresses,
                        request.expiresAt(),
                        request.enabled(),
                        0,
                        now),
                expectedRevision,
                key);
    }

    /** 撤销指定版本；已创建的 Broker 会在下一次连接前重新读取，因此不存在缓存宽限期。 */
    public boolean disable(String id, long revision, String key) {
        return repository.disable(id, revision, key);
    }

    /** 仅向受信任适配器提供已绑定用途的 Broker；不把授权数据交给子进程。 */
    public NetworkBroker broker(String workspaceId, String purpose) {
        return new HttpNetworkBroker((uri, address) -> permits(workspaceId, purpose, uri, address));
    }

    boolean permits(String workspaceId, String purpose, URI uri, InetAddress address) {
        if (!grantable(address) || workspaceId == null) {
            return false;
        }
        var workspace = workspace(workspaceId);
        if (workspace.locked()) {
            return false;
        }
        URI target = origin(uri);
        return repository.list(workspaceId).stream()
                .anyMatch(grant -> grant.enabled()
                        && grant.expiresAt().isAfter(Instant.now())
                        && grant.purpose().equals(purpose)
                        && grant.origin().equals(target)
                        && grant.addresses().contains(address.getHostAddress()));
    }

    /** 规范化准确 HTTP(S) 源；不接受 URL 中的身份信息、通配符或 IPv6 zone。 */
    public static URI origin(URI uri) {
        if (uri == null
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.isOpaque()
                || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost().contains("%")) {
            throw new IllegalArgumentException("invalid endpoint origin");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            try {
                // URI 已验证 IPv6 字面量；只规范化表示，不执行域名解析或放宽授权地址集合。
                host = "["
                        + InetAddress.getByName(host.substring(1, host.length() - 1))
                                .getHostAddress() + "]";
            } catch (java.net.UnknownHostException invalid) {
                throw new IllegalArgumentException("invalid IPv6 origin", invalid);
            }
        } else {
            host = java.net.IDN.toASCII(host, java.net.IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        }
        int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid endpoint port");
        }
        return URI.create(scheme + "://" + host + ":" + port);
    }

    static boolean grantable(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        return first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
    }

    private com.javaclaw.core.api.Workspace workspace(String id) {
        return workspaces
                .find(new com.javaclaw.core.api.WorkspaceId(id))
                .orElseThrow(() -> new IllegalArgumentException("workspace does not exist"));
    }
}
