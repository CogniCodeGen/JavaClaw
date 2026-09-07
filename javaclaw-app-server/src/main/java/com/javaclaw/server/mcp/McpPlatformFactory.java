package com.javaclaw.server.mcp;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.VaultState;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 创建 MCP 应用服务的组合边界，避免 App Server 主组合根承担协议细节。 */
public final class McpPlatformFactory {
    private static final URI LOOPBACK_CALLBACK = URI.create("http://127.0.0.1:17845/oauth/callback");

    private McpPlatformFactory() {}

    /**
     * 创建持久化 MCP Endpoint/Catalog 与 OAuth 状态机。
     *
     * @param platform 平台持久化、安全与生命周期依赖
     * @param runtime MCP 远端、签名 Bundle 与 Browser Worker 依赖
     * @return 不可变平台服务组
     */
    public static Services create(PlatformDependencies platform, RuntimeDependencies runtime) {
        PlatformDependencies checkedPlatform = Objects.requireNonNull(platform, "platform");
        RuntimeDependencies checkedRuntime = Objects.requireNonNull(runtime, "runtime");
        H2Database database = checkedPlatform.database();
        SecretVaultService vault = checkedPlatform.vault();
        McpRuntimePorts ports = checkedRuntime.ports();
        SignedBundleMcpSource signedBundles = checkedRuntime.signedBundles();
        CanonicalJson json = checkedPlatform.json();
        Clock clock = checkedPlatform.clock();
        Objects.requireNonNull(ports, "ports");
        McpRemotePort remote = new CompositeMcpRemotePort(
                ports.remote(),
                new SignedBundleMcpRemotePort(signedBundles, database.dataRoot().resolve("mcp-workers"), json, clock));
        McpService service = new McpService(
                database,
                new McpServiceDependencies(
                        remote,
                        ports.interactions(),
                        ports.network(),
                        reference -> vault.status().state() == VaultState.READY
                                && vault.metadata(reference).isPresent(),
                        Objects.requireNonNull(signedBundles, "signedBundles")),
                json,
                clock);
        McpOAuthService oauth =
                new McpOAuthService(database, service, vault, ports.oauth(), LOOPBACK_CALLBACK, json, clock);
        McpOAuthCoordinator coordinator = new McpOAuthCoordinator(
                oauth,
                service,
                vault,
                checkedPlatform.privateNetworkGrants(),
                checkedPlatform.extensions(),
                checkedPlatform.lifecycle(),
                checkedRuntime.browser());
        return new Services(service, oauth, coordinator);
    }

    /**
     * 平台级 MCP 依赖。
     *
     * @param database data-v6 数据库
     * @param vault Secret Vault
     * @param privateNetworkGrants 私网授权权威服务
     * @param extensions MCP 实时启停目录
     * @param lifecycle OAuth 活动 lease 协调器
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public record PlatformDependencies(
            H2Database database,
            SecretVaultService vault,
            PrivateNetworkGrantService privateNetworkGrants,
            ExtensionCatalogRepository extensions,
            LifecycleCoordinator lifecycle,
            CanonicalJson json,
            Clock clock) {
        /** 校验平台依赖。 */
        public PlatformDependencies {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(vault, "vault");
            Objects.requireNonNull(privateNetworkGrants, "privateNetworkGrants");
            Objects.requireNonNull(extensions, "extensions");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(clock, "clock");
        }
    }

    /**
     * 可替换的 MCP 运行时依赖。
     *
     * @param ports 真实或 fail-closed 外部端口
     * @param signedBundles 已验签 Bundle 实时来源
     * @param browser 签名发行镜像中的隔离 Browser Worker
     */
    public record RuntimeDependencies(
            McpRuntimePorts ports, SignedBundleMcpSource signedBundles, Optional<BrowserWorkerPort> browser) {
        /** 校验运行时依赖。 */
        public RuntimeDependencies {
            Objects.requireNonNull(ports, "ports");
            Objects.requireNonNull(signedBundles, "signedBundles");
            browser = Objects.requireNonNull(browser, "browser");
        }
    }

    /**
     * MCP RPC 与 Tool 平台共用的服务。
     *
     * @param service Endpoint、健康、Catalog 与 Tool 服务
     * @param oauth OAuth 2.1 PKCE 状态机
     * @param oauthCoordinator 隔离 Browser、实时撤权与 lifecycle 组合服务
     */
    public record Services(McpService service, McpOAuthService oauth, McpOAuthCoordinator oauthCoordinator) {
        /** 校验组合结果。 */
        public Services {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(oauth, "oauth");
            Objects.requireNonNull(oauthCoordinator, "oauthCoordinator");
        }
    }
}
