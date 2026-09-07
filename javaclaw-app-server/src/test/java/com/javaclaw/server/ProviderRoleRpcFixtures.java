package com.javaclaw.server;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.rpc.AppServerSession;

/** 通过真实 Protocol v3 安装测试 Provider 与 Agent Role。 */
public final class ProviderRoleRpcFixtures {
    private ProviderRoleRpcFixtures() {}

    /**
     * 安装首版 Provider 和 Role，并返回可用于 Turn 的精确引用。
     *
     * @param session 已完成 initialize 的 RPC 会话
     * @param components App Server 组件
     * @param installation 待安装的完整测试配置
     * @return Role 首版引用
     */
    public static AgentRoleRef install(
            AppServerSession session, AppServerBootstrap.Components components, Installation installation) {
        ProviderEndpointSpec providerSpec = ProviderEndpointTestFixtures.chat(
                "Test " + installation.providerId(), ProviderAdapter.OPENAI_COMPATIBLE, installation.model());
        ProviderRpcContracts.ProviderCreatePayload providerPayload = new ProviderRpcContracts.ProviderCreatePayload(
                installation.providerId(), providerSpec, ProviderLifecycle.ACTIVE);
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

        AgentRoleSpec roleSpec = new AgentRoleSpec(
                "Test " + installation.roleId(),
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                CapabilityNarrowing.inherit(),
                PermissionConstraint.INHERIT,
                Map.of());
        AgentRoleRpcContracts.CreatePayload rolePayload =
                new AgentRoleRpcContracts.CreatePayload(installation.roleId(), roleSpec);
        AgentRole role = decode(
                session.handle(request(
                        components,
                        "role-" + installation.roleId(),
                        "agent/role/create",
                        new WriteCommand(
                                "role-" + installation.roleId(),
                                0,
                                components.json().encode(rolePayload)))),
                components,
                AgentRole.class);
        installExecution(session, components, installation, role.ref());
        return role.ref();
    }

    private static void installExecution(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Installation installation,
            AgentRoleRef role) {
        ExecutionRpcContracts.ReadResult current = decode(
                session.handle(request(
                        components,
                        "read-default-" + installation.roleId(),
                        "execution/default/read",
                        new ExecutionRpcContracts.DefaultReadPayload(Optional.empty()))),
                components,
                ExecutionRpcContracts.ReadResult.class);
        long revision =
                current.configuration().map(ExecutionConfiguration::revision).orElse(0L);
        ExecutionOverrides execution = new ExecutionOverrides(
                Optional.of(role),
                Optional.of(new ProviderRef(installation.providerId(), 1, installation.model())),
                Optional.of(installation.permission()),
                Optional.of(ApprovalPolicy.NONE),
                Optional.of(installation.budget()),
                Optional.of(installation.visibleTools()),
                Optional.empty());
        decode(
                session.handle(request(
                        components,
                        "default-" + installation.roleId(),
                        "execution/default/update",
                        new WriteCommand(
                                "default-" + installation.roleId(),
                                revision,
                                components
                                        .json()
                                        .encode(new ExecutionRpcContracts.DefaultUpdatePayload(
                                                Optional.empty(), execution))))),
                components,
                ExecutionConfiguration.class);
    }

    /**
     * 一组必须原子配套的测试 Provider/Role 参数。
     *
     * @param providerId Provider 标识
     * @param model Provider 原生模型名
     * @param roleId Role 标识
     * @param permission 权限配置精确引用
     * @param visibleTools Role 可见工具上限
     * @param budget 默认 Turn 预算
     */
    public record Installation(
            String providerId,
            String model,
            String roleId,
            PermissionProfileRef permission,
            Set<String> visibleTools,
            TurnBudget budget) {
        /** 复制可见工具集合并校验必填值。 */
        public Installation {
            java.util.Objects.requireNonNull(providerId, "providerId");
            java.util.Objects.requireNonNull(model, "model");
            java.util.Objects.requireNonNull(roleId, "roleId");
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
