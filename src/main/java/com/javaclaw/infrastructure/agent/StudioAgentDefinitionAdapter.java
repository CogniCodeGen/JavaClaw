package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.application.agent.AgentDefinitionPort;
import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.AgentStudioClient;
import com.javaclaw.framework.api.DefinitionValidationResult;
import com.javaclaw.framework.api.StudioDraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Legacy settings-screen adapter backed exclusively by Agent Studio drafts and publications. */
public final class StudioAgentDefinitionAdapter implements AgentDefinitionPort {
    private final AgentStudioClient studio;
    private final String workspaceId;
    private final ObjectMapper json;

    public StudioAgentDefinitionAdapter(
            AgentStudioClient studio, String workspaceId, ObjectMapper json) {
        this.studio = Objects.requireNonNull(studio, "studio");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public List<com.javaclaw.framework.api.CapabilityForm> capabilityForms() {
        return studio.capabilityForms();
    }

    @Override
    public List<Agent> list() {
        return studio.agentDrafts(workspaceId).stream().map(this::toAgent).toList();
    }

    @Override
    public Agent create(String name) {
        AgentDefinitionDraft base = document(studio.agentDraft(workspaceId, "system.default"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String id = "user." + suffix;
        ObjectNode toolPolicy = copyObject(base.toolPolicy());
        toolPolicy.put("toolName", "custom_" + suffix);
        toolPolicy.put("enabled", true);
        toolPolicy.put("maxIterations", 1);
        LinkedHashMap<String, String> prompts = new LinkedHashMap<>(base.promptSections());
        prompts.put("description", "");
        prompts.put("system", "");
        StudioDraft saved = studio.saveAgentDraft(workspaceId, new AgentDefinitionDraft(
                id, name, base.modelPolicyRef(), prompts, base.capabilityBindings(), toolPolicy,
                base.permissionPolicy(), base.budgetPolicy(), base.outputContract(),
                base.compatibleExtensionRanges()));
        return toAgent(saved);
    }

    @Override
    public void update(Agent agent) {
        StudioDraft stored = studio.agentDraft(workspaceId, agent.id());
        if (stored.builtin()) throw new IllegalStateException("built-in definitions are read-only");
        AgentDefinitionDraft current = document(stored);
        LinkedHashMap<String, String> prompts = new LinkedHashMap<>(current.promptSections());
        prompts.put("description", agent.description());
        prompts.put("system", agent.systemPrompt());
        ObjectNode toolPolicy = copyObject(current.toolPolicy());
        toolPolicy.put("toolName", agent.toolName());
        toolPolicy.put("enabled", agent.enabled());
        toolPolicy.put("maxIterations", agent.maxIters());
        AgentDefinitionDraft updated = new AgentDefinitionDraft(
                current.id(), agent.name(), current.modelPolicyRef(), prompts,
                agent.capabilityBindings(), toolPolicy, current.permissionPolicy(),
                current.budgetPolicy(), current.outputContract(),
                current.compatibleExtensionRanges());
        studio.saveAgentDraft(workspaceId, updated);
        DefinitionValidationResult validation = studio.validateAgent(workspaceId, updated);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Agent Studio validation failed: " + validation.issues());
        }
        studio.publishAgent(workspaceId, agent.id());
    }

    @Override
    public boolean delete(String id) {
        return studio.archiveAgent(workspaceId, id);
    }

    private Agent toAgent(StudioDraft draft) {
        AgentDefinitionDraft definition = document(draft);
        String toolName = definition.toolPolicy().path("toolName").asText("");
        if (toolName.isBlank()) toolName = safeToolName(definition.id());
        String description = definition.promptSections().getOrDefault("description",
                definition.promptSections().getOrDefault("identity", ""));
        String system = definition.promptSections().getOrDefault("system", "");
        int iterations = Math.max(1,
                definition.toolPolicy().path("maxIterations").asInt(1));
        boolean enabled = definition.toolPolicy().path("enabled").asBoolean(true);
        return new Agent(definition.id(), definition.name(), toolName, description, system,
                Math.min(iterations, 30), enabled, draft.builtin(),
                definition.capabilityBindings());
    }

    private AgentDefinitionDraft document(StudioDraft draft) {
        return json.convertValue(draft.document(), AgentDefinitionDraft.class);
    }

    private static ObjectNode copyObject(com.fasterxml.jackson.databind.JsonNode source) {
        return source instanceof ObjectNode object
                ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
    }

    private static String safeToolName(String id) {
        String value = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (value.isBlank() || Character.isDigit(value.charAt(0))) value = "agent_" + value;
        return value;
    }
}
