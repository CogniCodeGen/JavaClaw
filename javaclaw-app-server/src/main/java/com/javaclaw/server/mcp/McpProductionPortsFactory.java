package com.javaclaw.server.mcp;

import java.time.Clock;
import java.util.Objects;

import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.security.PinnedHttpNetworkBroker;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 组装 App Server 默认 MCP HTTPS Host 的真实副作用边界。 */
public final class McpProductionPortsFactory {
    private McpProductionPortsFactory() {}

    /**
     * 创建可对外宣告的 MCP Host 端口。
     *
     * <p>HTTPS、Bearer/API Key 与 OAuth 2.1 metadata/PKCE/token 交换均经过 DNS 固定 Broker。返回的延迟交互工厂在
     * {@code AppServerRuntimeBootstrap} 中一次性绑定 {@code TurnMcpInteractionFactory}；绑定前 fail closed，绑定后 elicitation 经
     * {@code InputRequest}、sampling 经受预算 Turn 执行。
     *
     * @param vault Secret Vault
     * @param grants 私网授权服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     * @return 真实 HTTPS/OAuth 与等待 Runtime 绑定的交互端口组
     */
    public static McpRuntimePorts create(
            SecretVaultService vault, PrivateNetworkGrantService grants, CanonicalJson json, Clock clock) {
        BrokeredMcpRemotePort remote = new BrokeredMcpRemotePort(
                new PinnedHttpNetworkBroker(),
                Objects.requireNonNull(grants, "grants"),
                Objects.requireNonNull(vault, "vault"),
                Objects.requireNonNull(json, "json"),
                Objects.requireNonNull(clock, "clock"));
        BrokeredMcpOAuthPort oauth = new BrokeredMcpOAuthPort(new PinnedHttpNetworkBroker(), grants, json);
        return new McpRuntimePorts(remote, new DeferredMcpClientInteractionFactory(), remote::authorize, oauth, true);
    }
}
