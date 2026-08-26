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
import com.javaclaw.framework.api.ToolGroupAccess;
import com.javaclaw.framework.api.ToolNameAccess;
import com.javaclaw.framework.api.ToolAccessPolicy;
import com.javaclaw.runtime.WorkspaceContext;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Canonical product-side builder for requests entering the one AgentEngine. */
public final class RunRequestFactory {
    public static final String DEFAULT_AGENT = "system.default";
    private static final String LOCAL_USER = "local-user";

    private final WorkspaceContext workspace;
    private final ToolIntentRouter toolRouter;

    public RunRequestFactory(WorkspaceContext workspace) {
        this(workspace, null);
    }

    public RunRequestFactory(WorkspaceContext workspace, ToolIntentRouter toolRouter) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.toolRouter = toolRouter;
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
                        message.messageId(), message.role().name().toLowerCase(), message.content()))
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
        Map<String, com.fasterxml.jackson.databind.JsonNode> attributes = new LinkedHashMap<>();
        for (int index = request.priorMessages().size() - 1; index >= 0; index--) {
            ConversationMessage message = request.priorMessages().get(index);
            if (message.role() == ConversationMessage.Role.ASSISTANT) {
                attributes.put("previousAssistantReply",
                        JsonNodeFactory.instance.textNode(message.content()));
                break;
            }
        }
        if (toolRouter != null) {
            ToolExposureDecision decision = toolRouter.route(request);
            if (!decision.legacyAll()) {
                ToolAccessPolicy.restricted(
                        decision.allowedGroups(), decision.allowedTools())
                        .writeAttributes(attributes);
                boolean knowledgeContext = decision.bundleIds().contains("knowledge.read");
                attributes.put("framework.enableKnowledgeContext",
                        JsonNodeFactory.instance.booleanNode(knowledgeContext));
            }
            ObjectNode routing = JsonNodeFactory.instance.objectNode();
            routing.put("legacyAll", decision.legacyAll());
            routing.put("reason", decision.reason());
            var bundles = routing.putArray("bundles");
            decision.bundleIds().stream().sorted().forEach(bundles::add);
            routing.put("toolCount", decision.allowedTools().size());
            attributes.put("framework.toolRouting", routing);
        }
        if (!attributes.isEmpty()) builder.attributes(attributes);
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
