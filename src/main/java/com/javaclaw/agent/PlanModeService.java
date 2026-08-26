package com.javaclaw.agent;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.application.agent.AgentConversationRunner;
import com.javaclaw.application.agent.RunRequestFactory;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.runtime.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;

/** Read-only plan-profile adapter over the single AgentEngine. */
public final class PlanModeService {
    private static final Logger log = LoggerFactory.getLogger(PlanModeService.class);
    private static final String PLAN_COMPLETE_MARKER = "[PLAN_COMPLETE]";

    private final AgentConversationRunner runs;
    private final RunRequestFactory requests;

    public PlanModeService(
            AgentClient agents,
            WorkspaceContext workspace,
            com.javaclaw.config.AgentConfig settings,
            Executor executor) {
        this.runs = new AgentConversationRunner(
                Objects.requireNonNull(agents, "agents"), executor);
        this.requests = new RunRequestFactory(workspace,
                new com.javaclaw.application.agent.ToolIntentRouter(settings));
        log.info("Plan 入口已接入统一 AgentEngine，profile=plan");
    }

    public ConversationHandle planChat(
            ConversationRequest request, ConversationCallbacks callbacks) {
        return runs.start(requests.conversation(request, "plan",
                new InvocationSource("plan", "desktop"),
                        PermissionSet.of("tool.read", "memory.read", "knowledge.read",
                                "interaction.request")),
                ToolCallOrigin.INTERACTIVE, callbacks);
    }

    public boolean cancel() {
        return runs.cancel(CancellationReason.USER_REQUEST);
    }

    public boolean cancel(String sessionId) {
        return runs.cancelSession(sessionId, CancellationReason.USER_REQUEST);
    }

    public void clearHistory() {
        // Plan runs are stateless and durable; there is no coordinator-local memory to clear.
    }

    public void shutdown() {
        runs.close();
    }

    /** Compatibility event helper; Critic is now an EvaluationPolicy/extension, not a runtime. */
    static void publishFinalDraftAndReview(
            String finalDraft,
            ConversationCallbacks callbacks,
            boolean shouldRunCritic,
            java.util.function.BooleanSupplier cancellation,
            Runnable criticAction) {
        if (finalDraft == null || finalDraft.isBlank() || cancellation.getAsBoolean()) return;
        String guarded = com.javaclaw.util.ChineseOutputGuard.enforceUserVisibleReply(
                finalDraft.replace(PLAN_COMPLETE_MARKER, "").strip());
        callbacks.onEvent(new com.javaclaw.api.conversation.ConversationEvent.Custom(
                "plan_final", guarded));
        if (!shouldRunCritic || cancellation.getAsBoolean()) return;
        try {
            criticAction.run();
        } catch (RuntimeException failure) {
            if (!cancellation.getAsBoolean()) {
                log.warn("Plan evaluation failed; preserving final draft", failure);
                callbacks.onEvent(new com.javaclaw.api.conversation.ConversationEvent.Hint(
                        "[规划·评审] 评审未完成，已保留协调者最终方案"));
            }
        }
    }

    static java.util.Optional<com.javaclaw.api.conversation.PlanProfile> parsePlanProfile(
            String output) {
        if (output == null || output.isBlank()) return java.util.Optional.empty();
        java.util.EnumSet<com.javaclaw.api.conversation.PlanProfile> matches =
                java.util.EnumSet.noneOf(com.javaclaw.api.conversation.PlanProfile.class);
        var matcher = java.util.regex.Pattern.compile("\\b(QUICK|STANDARD|DEEP)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(output);
        while (matcher.find()) {
            matches.add(com.javaclaw.api.conversation.PlanProfile.valueOf(
                    matcher.group(1).toUpperCase(java.util.Locale.ROOT)));
        }
        return matches.size() == 1
                ? java.util.Optional.of(matches.iterator().next()) : java.util.Optional.empty();
    }
}
