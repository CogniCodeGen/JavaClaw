package com.javaclaw.desktop;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ThreadStatus;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;

public final class DesktopTestFixtures {
    public static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    private DesktopTestFixtures() {}

    public static Workspace workspace() {
        return new Workspace(
                WorkspaceId.parse("61e9d496-0798-49d8-a58e-f2337058e382"),
                "工作区",
                Path.of("/tmp/javaclaw-desktop"),
                com.javaclaw.api.WorkspaceLifecycle.ACTIVE,
                1,
                NOW,
                NOW);
    }

    public static ConversationThread thread() {
        return thread(workspace());
    }

    public static ConversationThread thread(Workspace workspace) {
        return new ConversationThread(
                ThreadId.parse("ee96ec76-c6cb-443d-806a-7186efc6b428"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "架构升级",
                ThreadStatus.ACTIVE,
                1,
                NOW,
                NOW);
    }

    public static AgentTurn turn() {
        return turn(thread(), TurnStatus.RUNNING, 1);
    }

    public static AgentTurn turn(ConversationThread thread, TurnStatus status, long revision) {
        AgentRole profile = profile();
        return new AgentTurn(
                TurnId.parse("686f3060-41b0-4d24-be52-226227701a77"),
                thread.id(),
                status,
                revision,
                new TurnBudget(1_000, 1_000, 8, 1, Duration.ofMinutes(1)),
                new AgentRoleRef(profile.id(), profile.revision()),
                profile.spec().model().orElseThrow().provider(),
                new PermissionProfileRef("default", 1),
                Path.of("."),
                "a".repeat(64),
                "b".repeat(64),
                Optional.empty(),
                NOW,
                NOW,
                resolved());
    }

    public static com.javaclaw.api.ResolvedTurnConfigSummary resolved() {
        return new com.javaclaw.api.ResolvedTurnConfigSummary(
                new AgentRoleRef("default", 1),
                new ProviderRef("openai", 1, "model-test"),
                new PermissionProfileRef("default", 1),
                com.javaclaw.api.ApprovalPolicy.RISKY,
                new TurnBudget(1_000, 1_000, 8, 1, Duration.ofMinutes(1)),
                Set.of("core/read"),
                Optional.empty(),
                "a".repeat(64),
                "b".repeat(64),
                true,
                java.util.List.of());
    }

    public static com.javaclaw.api.PromptManifestPreview promptPreview() {
        return new com.javaclaw.api.PromptManifestPreview(
                resolved().role(),
                resolved().provider(),
                resolved().permissionProfile(),
                java.util.List.of(),
                "a".repeat(64),
                100,
                "test",
                "测试模板",
                "");
    }

    public static AgentRole profile() {
        return new AgentRole(
                "default",
                1,
                RoleLifecycle.ACTIVE,
                new AgentRoleSpec(
                        "默认 Agent",
                        "角色测试",
                        "",
                        Optional.of(new com.javaclaw.api.ModelPreference(new ProviderRef("openai", 1, "model-test"))),
                        Optional.empty(),
                        new com.javaclaw.api.CapabilityNarrowing(Optional.of(Set.of("core/read")), Optional.empty()),
                        com.javaclaw.api.PermissionConstraint.INHERIT,
                        java.util.Map.of()),
                false,
                NOW,
                NOW);
    }

    public static ApprovalRecord approval(ApprovalState state) {
        ApprovalRequest request = new ApprovalRequest(
                "approval-1",
                turn().id(),
                "call-1",
                new ToolIdentity("core", "read_file", 1),
                ToolRisk.READ_ONLY,
                "读取文件",
                "a".repeat(64),
                NOW,
                NOW.plusSeconds(60));
        Optional<String> reason = state == ApprovalState.PENDING ? Optional.empty() : Optional.of("已处理");
        return new ApprovalRecord(request, state, 1, reason, NOW);
    }

    public static InputRequestRecord input() {
        InputRequest request = new InputRequest(
                "workflow-input-1",
                turn().id(),
                "workflow",
                "请选择发布方式并填写重试次数",
                new CanonicalPayload(
                        "{\"additionalProperties\":false,\"properties\":{\"confirmed\":{\"type\":\"boolean\"},\"mode\":{\"type\":\"string\"},\"retries\":{\"type\":\"integer\"}},\"required\":[\"confirmed\",\"mode\"],\"type\":\"object\"}"),
                NOW,
                NOW.plusSeconds(600));
        return new InputRequestRecord(request, InputRequestState.PENDING, 1, Optional.empty(), Optional.empty(), NOW);
    }

    public static ItemEnvelope item(long sequence) {
        return new ItemEnvelope(
                ItemId.random(),
                turn().id(),
                sequence,
                "test",
                "test/item@1",
                "test",
                ItemStatus.COMPLETED,
                new CanonicalPayload("{}"),
                NOW,
                Optional.of(NOW));
    }
}
