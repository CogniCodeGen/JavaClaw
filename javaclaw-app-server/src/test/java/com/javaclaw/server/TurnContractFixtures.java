package com.javaclaw.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.server.persistence.TurnPromptSnapshot;
import com.javaclaw.server.persistence.TurnStartRequest;

/** 不依赖 Provider 网络的 Turn 持久化契约夹具。 */
public final class TurnContractFixtures {
    /** 测试 Profile 引用。 */
    public static final AgentProfileRef PROFILE = new AgentProfileRef("test-profile", 1);
    /** 测试 Provider 引用。 */
    public static final ProviderRef PROVIDER = new ProviderRef("test-provider", 1, "test-model");
    /** 测试权限引用。 */
    public static final PermissionProfileRef PERMISSIONS = new PermissionProfileRef("standard", 1);
    /** 测试 Prompt snapshot。 */
    public static final CanonicalPayload PROMPT_SNAPSHOT = new CanonicalJson()
            .encode(new TurnPromptSnapshot(
                    "test-core-v1",
                    PROFILE,
                    PROVIDER,
                    new InstructionResolution(
                            List.of(),
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                            0,
                            0,
                            List.of(),
                            Instant.EPOCH),
                    "test system instruction"));
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
     * 创建显式 Profile 的 wire 启动参数。
     *
     * @param threadId Thread
     * @param message 用户消息
     * @return payload
     */
    public static CoreRpcContracts.TurnStartPayload payload(ThreadId threadId, String message) {
        return new CoreRpcContracts.TurnStartPayload(threadId, Optional.of(PROFILE), message);
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
        return new TurnStartRequest(
                threadId,
                budget,
                PROFILE,
                PROVIDER,
                PERMISSIONS,
                Path.of("."),
                PROMPT_SNAPSHOT,
                TOOL_CATALOG,
                message,
                Optional.empty());
    }

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
                PROFILE,
                PROVIDER,
                PERMISSIONS,
                Path.of("."),
                PROMPT_DIGEST,
                TOOL_DIGEST,
                Optional.empty(),
                now,
                now);
    }
}
