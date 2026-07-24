package com.javaclaw.mode;

import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Placement;
import com.javaclaw.workflow.service.WorkflowService;

/** 已发布自定义图的统一聊天入口。 */
public final class WorkflowMode implements ConversationMode {
    private final WorkflowService service;
    private volatile String selectedWorkflowId;
    private volatile String activeWorkflowId;
    private volatile String activeSessionId;

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
    public void start(ConversationRequest request, ConversationCallbacks callbacks) {
        String id = selectedWorkflowId;
        if (id == null || id.isBlank()) {
            callbacks.onError(new IllegalStateException("请先在工作流中心发布并选择一个工作流"));
            return;
        }
        activeWorkflowId = id;
        activeSessionId = request.sessionId();
        try {
            service.startOrResume(id, request.sessionId(), request.userInput(), callbacks);
        } catch (Throwable t) {
            activeWorkflowId = null;
            callbacks.onError(t);
        }
    }

    @Override
    public boolean cancel() {
        String id = activeWorkflowId;
        return id != null && service.cancel(id, activeSessionId);
    }
}
