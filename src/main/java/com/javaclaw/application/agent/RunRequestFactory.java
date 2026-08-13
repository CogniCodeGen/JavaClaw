package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.ConversationMessage;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.runtime.WorkspaceContext;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Canonical product-side builder for requests entering the one AgentEngine. */
public final class RunRequestFactory {
    public static final String DEFAULT_AGENT = "system.default";
    private static final String LOCAL_USER = "local-user";

    private final WorkspaceContext workspace;

    public RunRequestFactory(WorkspaceContext workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public RunRequest conversation(
            ConversationRequest request,
            String profile,
            InvocationSource source,
            PermissionSet permissions) {
        String sessionId = request.sessionId() == null
                ? "default" : request.sessionId();
        List<InputBlock> inputs = new ArrayList<>();
        request.priorMessages().stream()
                .map(message -> InputBlock.message(
                        message.role().name().toLowerCase(), message.content()))
                .forEach(inputs::add);
        inputs.add(InputBlock.text(request.userInput()));
        request.attachments().stream().map(RunRequestFactory::attachment).forEach(inputs::add);
        RunRequest.Builder builder = RunRequest.builder()
                .agent(AgentDefinitionRef.latest(DEFAULT_AGENT))
                .profile(RunProfileRef.latest(profile))
                .source(source)
                .scope(new RunScope(workspace.workspaceId(), LOCAL_USER, sessionId))
                .inputs(inputs)
                .linkage(RunLinkage.root(null))
                .permissionCeiling(permissions)
                .budget(RunBudget.UNBOUNDED);
        for (int index = request.priorMessages().size() - 1; index >= 0; index--) {
            ConversationMessage message = request.priorMessages().get(index);
            if (message.role() == ConversationMessage.Role.ASSISTANT) {
                ObjectNode previous = JsonNodeFactory.instance.objectNode();
                previous.put("previousAssistantReply", message.content());
                java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> attributes =
                        new java.util.LinkedHashMap<>();
                previous.fields().forEachRemaining(entry ->
                        attributes.put(entry.getKey(), entry.getValue()));
                builder.attributes(attributes);
                break;
            }
        }
        return builder.build();
    }

    public RunRequest text(
            String prompt,
            String sessionId,
            String profile,
            InvocationSource source,
            PermissionSet permissions,
            String idempotencyKey) {
        return RunRequest.builder()
                .agent(AgentDefinitionRef.latest(DEFAULT_AGENT))
                .profile(RunProfileRef.latest(profile))
                .source(source)
                .scope(new RunScope(workspace.workspaceId(), LOCAL_USER,
                        sessionId == null || sessionId.isBlank() ? "default" : sessionId))
                .input(InputBlock.text(prompt == null ? "" : prompt))
                .linkage(RunLinkage.root(null))
                .permissionCeiling(permissions)
                .budget(RunBudget.UNBOUNDED)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private static InputBlock attachment(File file) {
        String mediaType;
        try {
            mediaType = Files.probeContentType(file.toPath());
        } catch (Exception ignored) {
            mediaType = null;
        }
        if (mediaType == null || mediaType.isBlank()) mediaType = "application/octet-stream";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("name", file.getName());
        data.put("uri", file.toURI().toString());
        data.put("mediaType", mediaType);
        return new InputBlock(mediaType.startsWith("image/") ? "core.image" : "core.file", data);
    }
}
