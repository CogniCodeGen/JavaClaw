package com.javaclaw.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.rpc.AppServerSession;

/** 通过真实 Protocol v2 安装测试 Provider 与 Agent Profile。 */
public final class ProviderProfileRpcFixtures {
    private ProviderProfileRpcFixtures() {}

    /**
     * 安装首版 Provider 和 Profile，并返回可用于 Turn 的精确引用。
     *
     * @param session 已完成 initialize 的 RPC 会话
     * @param components App Server 组件
     * @param installation 待安装的完整测试配置
     * @return Profile 首版引用
     */
    public static AgentProfileRef install(
            AppServerSession session, AppServerBootstrap.Components components, Installation installation) {
        ProviderEndpointSpec providerSpec = new ProviderEndpointSpec(
                "Test " + installation.providerId(),
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of(installation.model()),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                Map.of());
        ProviderProfileRpcContracts.ProviderCreatePayload providerPayload =
                new ProviderProfileRpcContracts.ProviderCreatePayload(installation.providerId(), providerSpec);
        decode(
                session.handle(request(
                        components,
                        "provider-" + installation.providerId(),
                        "provider/create",
                        new WriteCommand(
                                "provider-" + installation.providerId(),
                                0,
                                components.json().encode(providerPayload)))),
                components,
                ProviderEndpoint.class);

        AgentProfileSpec profileSpec = new AgentProfileSpec(
                "Test " + installation.profileId(),
                "",
                new ProviderRef(installation.providerId(), 1, installation.model()),
                installation.permission(),
                installation.visibleTools(),
                installation.budget());
        ProviderProfileRpcContracts.AgentProfileCreatePayload profilePayload =
                new ProviderProfileRpcContracts.AgentProfileCreatePayload(installation.profileId(), profileSpec);
        AgentProfile profile = decode(
                session.handle(request(
                        components,
                        "profile-" + installation.profileId(),
                        "profile/create",
                        new WriteCommand(
                                "profile-" + installation.profileId(),
                                0,
                                components.json().encode(profilePayload)))),
                components,
                AgentProfile.class);
        return new AgentProfileRef(profile.id(), profile.revision());
    }

    /**
     * 一组必须原子配套的测试 Provider/Profile 参数。
     *
     * @param providerId Provider 标识
     * @param model Provider 原生模型名
     * @param profileId Profile 标识
     * @param permission 权限配置精确引用
     * @param visibleTools Profile 可见工具上限
     * @param budget 默认 Turn 预算
     */
    public record Installation(
            String providerId,
            String model,
            String profileId,
            PermissionProfileRef permission,
            Set<String> visibleTools,
            TurnBudget budget) {
        /** 复制可见工具集合并校验必填值。 */
        public Installation {
            java.util.Objects.requireNonNull(providerId, "providerId");
            java.util.Objects.requireNonNull(model, "model");
            java.util.Objects.requireNonNull(profileId, "profileId");
            java.util.Objects.requireNonNull(permission, "permission");
            visibleTools = Set.copyOf(visibleTools);
            java.util.Objects.requireNonNull(budget, "budget");
        }
    }

    private static JsonRpcRequest request(
            AppServerBootstrap.Components components, String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private static <T> T decode(JsonRpcResponse response, AppServerBootstrap.Components components, Class<T> type) {
        return components
                .json()
                .decode(
                        response.result()
                                .orElseThrow(() ->
                                        new AssertionError(response.error().orElseThrow())),
                        type);
    }
}
