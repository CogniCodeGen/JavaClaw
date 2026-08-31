package com.javaclaw.agent.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.WorkspaceId;

/** 计划采用只引用真实 Plan Item，权限仍由 Profile 与正常工具治理解析；不恢复 Markdown 哨兵驱动状态。 */
public final class PlanAdoptionService implements PlanAdoptionUseCases {
    private final ProfileUseCases profiles;
    private final WorkspaceUseCases workspaces;
    private final ThreadUseCases threads;
    private final TurnUseCases turns;

    /** 注入窄领域用例；不依赖 Provider、协议、持久化实现或 UI。 */
    public PlanAdoptionService(
            ProfileUseCases profiles, WorkspaceUseCases workspaces, ThreadUseCases threads, TurnUseCases turns) {
        this.profiles = Objects.requireNonNull(profiles);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.threads = Objects.requireNonNull(threads);
        this.turns = Objects.requireNonNull(turns);
    }

    @Override
    public AgentTurn adopt(
            ThreadId threadId,
            ItemId planItemId,
            String profileId,
            long expectedProfileRevision,
            String decisions,
            String idempotencyKey) {
        if (decisions == null
                || decisions.length() > 16_384
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 256) {
            throw new IllegalArgumentException("bounded decisions and idempotency key are required");
        }
        String request = PromptHashes.sequence(List.of(
                threadId.value(), planItemId.value(), profileId, Long.toString(expectedProfileRevision), decisions));
        String key = "plan-adopt-" + PromptHashes.sha256(idempotencyKey);
        var previous = threads.readTurnByIdempotencyKey(threadId, key);
        if (previous.isPresent()) {
            if (!request.equals(previous.get().config().attributes().get("planAdoptionRequestHash"))) {
                throw new IllegalStateException("plan adoption idempotency key has different input");
            }
            return previous.get();
        }
        var snapshot = threads.readThread(threadId).orElseThrow(() -> new NoSuchElementException("thread not found"));
        var stored = snapshot.items().stream()
                .filter(value -> value.id().equals(planItemId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Plan Item is not in this Thread"));
        if (stored.state() != ItemState.COMPLETED || !(stored.item() instanceof ThreadItem.Plan plan)) {
            throw new IllegalArgumentException("only a completed Plan Item can be adopted");
        }
        var source = threads.readTurn(stored.turnId()).orElseThrow();
        if (source.status() != com.javaclaw.core.api.TurnStatus.COMPLETED
                || !"PLAN".equals(source.config().attributes().get("profileKind"))
                || plan.details().goal().isBlank()
                || plan.steps().isEmpty()
                || plan.details().acceptanceCriteria().isEmpty()) {
            // 自动化进度也可能表现为 Plan Item，不能把它误当作经过完整规划的可采用版本。
            throw new IllegalArgumentException("only a successful PLAN Turn with acceptance criteria can be adopted");
        }
        if (!plan.details().openQuestions().isEmpty() && decisions.isBlank()) {
            throw new IllegalArgumentException("open Plan decisions require explicit user answers");
        }
        var workspace = workspaces
                .readWorkspace(new WorkspaceId(snapshot.thread().workspaceId()))
                .orElseThrow();
        var resolved = profiles.resolve(profileId, workspace, ApprovalPolicy.ON_RISK, null);
        if (resolved.profile().kind() != ProfileKind.CHAT || resolved.profile().revision() != expectedProfileRevision) {
            throw new IllegalStateException("select an unchanged CHAT Profile for execution");
        }
        StringBuilder input = new StringBuilder("用户通过计划采用操作明确授权执行以下已保存计划。仍遵守当前工具、审批、沙箱与预算。\n目标：")
                .append(plan.details().goal())
                .append("\n范围：")
                .append(plan.details().scope())
                .append("\n步骤：\n");
        plan.steps().forEach(step -> input.append("- ").append(step.step()).append('\n'));
        input.append("\n验收条件：").append(String.join("\n", plan.details().acceptanceCriteria()));
        input.append("\n依赖：").append(String.join("\n", plan.details().dependencies()));
        input.append("\n风险：")
                .append(String.join("\n", plan.details().risks()))
                .append("\n用户决策：")
                .append(decisions);
        var original = resolved.turnConfig();
        var attributes = new LinkedHashMap<>(original.attributes());
        attributes.put("adoptedPlanItemId", planItemId.value());
        attributes.put("adoptedPlanHash", PromptHashes.sha256(input.toString()));
        attributes.put("planAdoptionRequestHash", request);
        var config = new TurnConfig(
                original.model(),
                original.provider(),
                original.reasoningEffort(),
                original.workingDirectory(),
                original.sandboxPolicy(),
                original.approvalPolicy(),
                original.enabledTools(),
                attributes);
        return turns.startTurn(
                new TurnStartCommand(threadId, List.of(new TurnInput.Text(input.toString())), config, key));
    }
}
