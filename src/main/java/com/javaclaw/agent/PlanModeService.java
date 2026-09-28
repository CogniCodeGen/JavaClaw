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
    private final AgentConversationRunner runs;
    private final RunRequestFactory requests;

    public PlanModeService(
            AgentClient agents,
            WorkspaceContext workspace,
            Executor executor) {
        this.runs = new AgentConversationRunner(
                Objects.requireNonNull(agents, "agents"), executor);
        this.requests = new RunRequestFactory(workspace);
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
}
