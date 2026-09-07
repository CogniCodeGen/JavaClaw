package com.javaclaw.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.ModelInstructions;
import com.javaclaw.server.persistence.TurnPromptSnapshot;
import com.javaclaw.server.persistence.TurnStartRequest;

/** 不依赖 Provider 网络的 Turn 持久化契约夹具。 */
public final class TurnContractFixtures {
    /** 测试 Role 引用。 */
    public static final AgentRoleRef ROLE = new AgentRoleRef("test-profile", 1);
    /** 测试 Provider 引用。 */
    public static final ProviderRef PROVIDER = new ProviderRef("test-provider", 1, "test-model");
    /** 测试权限引用。 */
    public static final PermissionProfileRef PERMISSIONS = new PermissionProfileRef("standard", 1);
    /** 测试 Prompt snapshot。 */
    public static final CanonicalPayload PROMPT_SNAPSHOT = new CanonicalJson()
            .encode(new TurnPromptSnapshot(
                    "test-core-v1",
                    ROLE,
                    PROVIDER,
                    new InstructionResolution(
                            List.of(),
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                            0,
                            0,
                            List.of(),
                            Instant.EPOCH),
                    List.of(),
                    new ModelInstructions("test system instruction", "", "")));
    /** 测试 Prompt manifest 摘要。 */
    public static final String PROMPT_DIGEST = PROMPT_SNAPSHOT.sha256();
    /** 测试工具目录。 */
    public static final ToolCatalogSnapshot TOOL_CATALOG = new ToolCatalogSnapshot(
            TurnId.random(),
            1,
            List.of(),
            new PermissionProfile(
                    PERMISSIONS.id(),
                    PERMISSIONS.version(),
                    new FilePermission(List.of(), List.of(), false, false),
                    new NetworkPermission(Set.of(), Set.of(), true),
                    new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                    new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                    new ResourceLimits(1, 1, 1, 1)),
            Instant.EPOCH);
    /** 测试工具目录摘要。 */
    public static final String TOOL_DIGEST = TOOL_CATALOG.digest();

    private TurnContractFixtures() {}

    /**
     * 创建显式 Role 的 wire 启动参数。
     *
     * @param threadId Thread
     * @param message 用户消息
     * @return payload
     */
    public static CoreRpcContracts.TurnStartPayload payload(ThreadId threadId, String message) {
        return new CoreRpcContracts.TurnStartPayload(threadId, select(ROLE), message, List.of());
    }

    /**
     * 创建已由服务端解析的持久化请求。
     *
     * @param threadId Thread
     * @param budget 预算
     * @param message 用户消息
     * @return request
     */
    public static TurnStartRequest request(ThreadId threadId, TurnBudget budget, CorePayloads.Message message) {
        return request(
                threadId,
                new Selection(budget, ROLE, PROVIDER, PERMISSIONS),
                Path.of("."),
                PROMPT_SNAPSHOT,
                TOOL_CATALOG,
                message,
                Optional.empty());
    }

    /**
     * 复用同一快照生成字段一致的持久化测试请求。
     *
     * @param threadId 所属 Thread
     * @param selection 精确执行引用与预算
     * @param root 执行根
     * @param prompt 冻结 Prompt
     * @param catalog 冻结工具目录
     * @param message 首条用户消息
     * @param scope 无人值守来源，没有时为空
     * @return 不含旧 Profile 契约的 v6 请求
     */
    public static TurnStartRequest request(
            ThreadId threadId,
            Selection selection,
            Path root,
            CanonicalPayload prompt,
            ToolCatalogSnapshot catalog,
            CorePayloads.Message message,
            Optional<UnattendedExecutionScope> scope) {
        return new TurnStartRequest(
                threadId, configuration(selection, prompt, catalog), root, prompt, catalog, message, scope);
    }

    /**
     * 构造与快照摘要一致的测试执行配置。
     *
     * @param selection 精确引用与预算
     * @param prompt 冻结 Prompt
     * @param catalog 冻结工具目录
     * @return 完整不可变配置
     */
    public static ResolvedTurnConfig configuration(
            Selection selection, CanonicalPayload prompt, ToolCatalogSnapshot catalog) {
        return new ResolvedTurnConfig(
                selection.role(),
                selection.provider(),
                selection.permission(),
                ApprovalPolicy.valueOf(catalog.permissionCeiling()
                        .tools()
                        .approvalRequirement()
                        .name()),
                selection.budget(),
                catalog.tools().stream()
                        .map(tool -> tool.identity().name())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                Optional.empty(),
                PermissionConstraint.INHERIT,
                Optional.empty(),
                prompt.sha256(),
                catalog.digest(),
                List.of());
    }

    /**
     * 构造只选择 Role、其余字段继承的 wire 配置。
     *
     * @param role 精确 Role
     * @return 只有 Role 显式值的执行选择
     */
    public static ExecutionOverrides select(AgentRoleRef role) {
        return new ExecutionOverrides(
                Optional.of(role),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * 测试运行直接提供的配置引用，不代表生产配置解析器。
     *
     * @param budget 有限预算
     * @param role 精确 Role
     * @param provider 精确 Provider
     * @param permission 精确权限引用
     */
    public record Selection(
            TurnBudget budget, AgentRoleRef role, ProviderRef provider, PermissionProfileRef permission) {}

    /**
     * 创建独立 AgentTurn 快照。
     *
     * @param threadId Thread
     * @param status 状态
     * @param budget 预算
     * @param now 时间
     * @return Turn
     */
    public static AgentTurn turn(ThreadId threadId, TurnStatus status, TurnBudget budget, Instant now) {
        return new AgentTurn(
                TurnId.random(),
                threadId,
                status,
                1,
                budget,
                ROLE,
                PROVIDER,
                PERMISSIONS,
                Path.of("."),
                PROMPT_DIGEST,
                TOOL_DIGEST,
                Optional.empty(),
                now,
                now,
                configuration(new Selection(budget, ROLE, PROVIDER, PERMISSIONS), PROMPT_SNAPSHOT, TOOL_CATALOG)
                        .summary());
    }
}
