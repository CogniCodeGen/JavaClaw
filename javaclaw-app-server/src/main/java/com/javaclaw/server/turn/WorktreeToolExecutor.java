package com.javaclaw.server.turn;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;

/** 已通过工具权限与人工审批后执行现有 Worktree 原子应用用例。 */
final class WorktreeToolExecutor {
    private final CoreCommandService core;
    private final ManagedWorktreeService worktrees;
    private final CanonicalJson json;
    private final Clock clock;

    WorktreeToolExecutor(CoreCommandService core, ManagedWorktreeService worktrees, CanonicalJson json, Clock clock) {
        this.core = core;
        this.worktrees = worktrees;
        this.json = json;
        this.clock = clock;
    }

    ToolExecutionOutcome execute(ToolCallRequest request) {
        WorktreeApplyArguments arguments = json.decode(request.arguments(), WorktreeApplyArguments.class);
        AgentTurn turn = core.findTurn(request.turnId())
                .orElseThrow(() -> new TurnFailureException("TURN_NOT_FOUND", "工具所属 Turn 不存在"));
        com.javaclaw.api.ManagedWorktree applied = worktrees.apply(
                request.idempotencyKey(), turn.id(), WorktreeId.parse(arguments.worktreeId()), arguments.patchDigest());
        WorktreeApplyResult response = new WorktreeApplyResult(
                applied.id().toString(),
                arguments.patchDigest(),
                applied.state().name(),
                applied.revision());
        com.javaclaw.api.CanonicalPayload payload = json.encode(response);
        EffectReceipt receipt = new EffectReceipt(
                request.idempotencyKey(),
                CoreTools.WORKTREE_APPLY_NAME,
                request.arguments().sha256(),
                payload.sha256(),
                Instant.now(clock));
        ToolCallResult result = new ToolCallResult(request.callId(), true, payload, Optional.of(receipt));
        return ToolExecutionOutcome.resultOnly(result);
    }

    private record WorktreeApplyArguments(String worktreeId, String patchDigest) {
        private WorktreeApplyArguments {
            worktreeId = Objects.requireNonNull(worktreeId, "worktreeId").strip();
            patchDigest =
                    Objects.requireNonNull(patchDigest, "patchDigest").strip().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private record WorktreeApplyResult(String worktreeId, String patchDigest, String state, long revision) {}
}
