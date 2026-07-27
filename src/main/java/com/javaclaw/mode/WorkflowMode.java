package com.javaclaw.mode;

import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.DefaultConversationHandle;
import com.javaclaw.api.conversation.Placement;
import com.javaclaw.api.conversation.TerminalCallbackGuard;
import com.javaclaw.workflow.service.WorkflowService;

import java.util.function.BiPredicate;

/** 已发布自定义图的统一聊天入口。 */
public final class WorkflowMode implements ConversationMode {
    private final WorkflowService service;
    private volatile String selectedWorkflowId;

    public WorkflowMode(WorkflowService service) { this.service = service; }
    @Override public String id() { return "workflow"; }
    @Override public String displayName() { return "⎇ 工作流"; }
    @Override public String tooltip() { return "运行工作流中心中已发布的 Java 原生状态图"; }
    @Override public Placement placement() { return Placement.TOP_SEGMENT; }
    @Override public Capabilities capabilities() { return new Capabilities(false, true, true, false, false); }

    public void selectWorkflow(String id) { selectedWorkflowId = id; }
    public String selectedWorkflowId() { return selectedWorkflowId; }
    public WorkflowService service() { return service; }

    @Override
    public ConversationHandle start(ConversationRequest request, ConversationCallbacks callbacks) {
        String id = selectedWorkflowId;
        String sessionId = request.sessionId();
        var guarded = new TerminalCallbackGuard(callbacks);
        var handle = createRunHandle(guarded, id, sessionId, service::cancel);
        if (id == null || id.isBlank()) {
            guarded.onTerminal(ConversationOutcome.failed(
                    new IllegalStateException("请先在工作流中心发布并选择一个工作流")));
            return handle;
        }
        try {
            service.startOrResume(id, sessionId, request.userInput(), guarded);
        } catch (Throwable t) {
            guarded.onTerminal(ConversationOutcome.failed(t));
        }
        return handle;
    }

    static ConversationHandle createRunHandle(
            TerminalCallbackGuard guarded,
            String workflowId,
            String sessionId,
            BiPredicate<String, String> canceller) {
        return new DefaultConversationHandle(
                guarded,
                ignored -> workflowId != null
                        && !workflowId.isBlank()
                        && canceller.test(workflowId, sessionId));
    }
}
