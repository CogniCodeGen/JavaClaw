package com.javaclaw.workflow.service;

import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.NodeExecutionContext;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 系统图调用快照；恢复时只从持久化状态重建请求，不捕获后续用户消息。 */
public final class SystemInvocationState {
    private static final String ATTACHMENTS = "_system.request.attachments";
    private static final String SESSION_ID = "_system.request.sessionId";
    private static final String PLAN_PROFILE = "_system.request.planProfile";

    private SystemInvocationState() {}

    public static GraphState from(ConversationRequest request) {
        ConversationRequest safe = request == null ? ConversationRequest.ofText("") : request;
        List<String> paths = safe.attachments().stream()
                .map(File::getAbsolutePath).toList();
        return new GraphState().apply(StatePatch.builder()
                .set("input", safe.userInput())
                .set(ATTACHMENTS, paths)
                .set(SESSION_ID, safe.sessionId() == null ? "" : safe.sessionId())
                .set(PLAN_PROFILE, safe.options().planProfile().name())
                .build());
    }

    public static ConversationRequest request(NodeExecutionContext context) {
        return request(context.state());
    }

    public static ConversationRequest request(GraphState state) {
        List<File> attachments = new ArrayList<>();
        for (var path : state.get(ATTACHMENTS)) {
            if (path.isTextual() && !path.asText().isBlank()) attachments.add(new File(path.asText()));
        }
        String sessionId = state.get(SESSION_ID).asText();
        String profileText = state.get(PLAN_PROFILE).asText("AUTO");
        com.javaclaw.api.conversation.PlanProfile profile;
        try {
            profile = com.javaclaw.api.conversation.PlanProfile.valueOf(profileText);
        } catch (IllegalArgumentException e) {
            profile = com.javaclaw.api.conversation.PlanProfile.AUTO;
        }
        return new ConversationRequest(state.get("input").asText(), attachments,
                sessionId.isBlank() ? null : sessionId,
                new com.javaclaw.api.conversation.ConversationOptions(profile));
    }
}
