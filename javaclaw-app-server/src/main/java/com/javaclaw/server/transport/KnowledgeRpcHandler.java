package com.javaclaw.server.transport;

import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.knowledge.KnowledgeUseCases;
import com.javaclaw.protocol.RpcMethods;

/** Knowledge, memory and skill protocol adapter. */
final class KnowledgeRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.KNOWLEDGE_SOURCE_LIST,
            RpcMethods.KNOWLEDGE_SOURCE_IMPORT,
            RpcMethods.KNOWLEDGE_SOURCE_READ,
            RpcMethods.KNOWLEDGE_SOURCE_DELETE,
            RpcMethods.KNOWLEDGE_SOURCE_REINDEX,
            RpcMethods.KNOWLEDGE_SOURCE_STATS,
            RpcMethods.KNOWLEDGE_SEARCH,
            RpcMethods.MEMORY_LIST,
            RpcMethods.MEMORY_PUT,
            RpcMethods.MEMORY_DELETE,
            RpcMethods.MEMORY_READ,
            RpcMethods.MEMORY_HISTORY,
            RpcMethods.MEMORY_SAVE,
            RpcMethods.MEMORY_RESTORE,
            RpcMethods.MEMORY_PROPOSE,
            RpcMethods.MEMORY_PROPOSALS,
            RpcMethods.MEMORY_REVIEW,
            RpcMethods.SKILL_LIST,
            RpcMethods.KNOWLEDGE_SOURCE_HISTORY,
            RpcMethods.KNOWLEDGE_SOURCE_CONTENT,
            RpcMethods.SKILL_READ,
            RpcMethods.SKILL_HISTORY,
            RpcMethods.SKILL_RESTORE,
            RpcMethods.SKILL_RESOURCE_READ,
            RpcMethods.SKILL_PROPOSE,
            RpcMethods.SKILL_PROPOSALS,
            RpcMethods.SKILL_REVIEW,
            RpcMethods.LEARNING_READ,
            RpcMethods.LEARNING_CONFIGURE,
            RpcMethods.SKILL_INSTALL,
            RpcMethods.SKILL_ENABLE,
            RpcMethods.SKILL_DISABLE,
            RpcMethods.SKILL_UNINSTALL);

    private final KnowledgeUseCases knowledge;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    KnowledgeRpcHandler(KnowledgeUseCases knowledge, ObjectMapper json, ProtocolMapper wire) {
        this.knowledge = knowledge;
        this.json = Objects.requireNonNull(json, "json");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public JsonNode handle(String method, JsonNode params) {
        KnowledgeUseCases service = requireService();
        try {
            return switch (method) {
                case RpcMethods.LEARNING_READ ->
                    json.valueToTree(wire.learningSettings(
                            service.learningSettings(RequestParameters.requiredText(params, "workspaceId"))));
                case RpcMethods.LEARNING_CONFIGURE ->
                    json.valueToTree(wire.learningSettings(service.saveLearningSettings(
                            RequestParameters.requiredText(params, "workspaceId"),
                            RequestParameters.requiredText(params, "skillMode"),
                            RequestParameters.optionalBoolean(params, "memoryAutomatic", true),
                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.SKILL_READ ->
                    json.valueToTree(wire.skill(service.readSkill(RequestParameters.requiredText(params, "skillId"))));
                case RpcMethods.SKILL_HISTORY ->
                    json.valueToTree(service.skillHistory(RequestParameters.requiredText(params, "skillId")).stream()
                            .map(wire::skill)
                            .toList());
                case RpcMethods.SKILL_RESTORE ->
                    json.valueToTree(wire.skill(service.restoreSkill(
                            RequestParameters.requiredText(params, "skillId"),
                            RequestParameters.optionalLong(params, "sourceRevision", -1),
                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.SKILL_RESOURCE_READ ->
                    json.valueToTree(wire.skillResource(service.readSkillResource(
                            RequestParameters.requiredText(params, "skillId"),
                            RequestParameters.optionalLong(params, "revision", -1),
                            RequestParameters.requiredText(params, "path"))));
                case RpcMethods.SKILL_PROPOSALS ->
                    json.valueToTree(
                            service.skillProposals(RequestParameters.requiredText(params, "workspaceId")).stream()
                                    .map(wire::skillProposal)
                                    .toList());
                case RpcMethods.SKILL_PROPOSE ->
                    json.valueToTree(wire.skillProposal(service.proposeSkill(
                            skillDraft(params),
                            RequestParameters.optionalText(params, "reason", "用户提交的 Skill 候选"),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.SKILL_REVIEW ->
                    json.valueToTree(wire.skillProposal(service.reviewSkillProposal(
                            RequestParameters.requiredText(params, "proposalId"),
                            RequestParameters.optionalBoolean(params, "accept", false),
                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.KNOWLEDGE_SOURCE_HISTORY ->
                    json.valueToTree(service.sourceHistory(RequestParameters.requiredText(params, "sourceId")).stream()
                            .map(wire::knowledgeGeneration)
                            .toList());
                case RpcMethods.KNOWLEDGE_SOURCE_CONTENT ->
                    json.getNodeFactory()
                            .textNode(service.sourceContent(
                                    RequestParameters.requiredText(params, "sourceId"),
                                    RequestParameters.optionalLong(params, "revision", -1)));
                case RpcMethods.MEMORY_READ ->
                    json.valueToTree(
                            wire.memoryDetail(service.readMemory(RequestParameters.requiredText(params, "memoryId"))));
                case RpcMethods.MEMORY_HISTORY ->
                    json.valueToTree(service.memoryHistory(RequestParameters.requiredText(params, "memoryId")).stream()
                            .map(wire::memoryDetail)
                            .toList());
                case RpcMethods.MEMORY_SAVE ->
                    json.valueToTree(wire.memoryDetail(service.saveMemory(
                            memoryDraft(params),
                            RequestParameters.optionalLong(params, "expectedRevision", 0),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.MEMORY_RESTORE ->
                    json.valueToTree(wire.memoryDetail(service.restoreMemory(
                            RequestParameters.requiredText(params, "memoryId"),
                                    RequestParameters.optionalLong(params, "sourceRevision", -1),
                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                                    RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.MEMORY_PROPOSE ->
                    json.valueToTree(wire.memoryProposal(service.proposeMemory(
                            memoryDraft(params),
                            RequestParameters.optionalLong(params, "expectedRevision", 0),
                            RequestParameters.optionalText(params, "reason", "用户提交的建议"),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.MEMORY_PROPOSALS ->
                    json.valueToTree(
                            service.memoryProposals(RequestParameters.requiredText(params, "workspaceId")).stream()
                                    .map(wire::memoryProposal)
                                    .toList());
                case RpcMethods.MEMORY_REVIEW ->
                    json.valueToTree(wire.memoryProposal(service.reviewMemoryProposal(
                            RequestParameters.requiredText(params, "proposalId"),
                                    RequestParameters.optionalBoolean(params, "accept", false),
                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                                    RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.KNOWLEDGE_SOURCE_LIST ->
                    json.valueToTree(service.listSources(RequestParameters.requiredText(params, "workspaceId")).stream()
                            .map(wire::knowledgeSource)
                            .toList());
                case RpcMethods.KNOWLEDGE_SOURCE_STATS ->
                    json.valueToTree(service.sourceStats(RequestParameters.requiredText(params, "workspaceId")).stream()
                            .map(wire::knowledgeSourceStats)
                            .toList());
                case RpcMethods.KNOWLEDGE_SOURCE_READ ->
                    json.valueToTree(wire.knowledgeSource(
                            service.readSource(RequestParameters.requiredText(params, "sourceId"))));
                case RpcMethods.KNOWLEDGE_SOURCE_IMPORT ->
                    json.valueToTree(wire.knowledgeSource(service.importAttachment(
                            RequestParameters.requiredText(params, "workspaceId"),
                            RequestParameters.requiredText(params, "sha256"),
                            RequestParameters.requiredText(params, "displayName"),
                            RequestParameters.requiredText(params, "mediaType"),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.KNOWLEDGE_SOURCE_REINDEX ->
                    json.valueToTree(wire.knowledgeSource(service.reindex(
                            RequestParameters.requiredText(params, "sourceId"),
                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.KNOWLEDGE_SOURCE_DELETE ->
                    RpcResults.flag(
                            "deleted",
                            service.deleteSource(
                                    RequestParameters.requiredText(params, "sourceId"),
                                    RequestParameters.optionalLong(params, "expectedRevision", -1),
                                    RequestParameters.optionalText(params, "idempotencyKey", null)));
                case RpcMethods.KNOWLEDGE_SEARCH ->
                    json.valueToTree(service
                            .search(
                                    RequestParameters.requiredText(params, "workspaceId"),
                                    RequestParameters.requiredText(params, "query"),
                                    Math.toIntExact(RequestParameters.optionalLong(params, "limit", 10)))
                            .stream()
                            .map(wire::knowledgeHit)
                            .toList());
                case RpcMethods.MEMORY_LIST ->
                    json.valueToTree(
                            service.listMemories(RequestParameters.requiredText(params, "workspaceId")).stream()
                                    .map(wire::memory)
                                    .toList());
                case RpcMethods.MEMORY_PUT ->
                    json.valueToTree(wire.memory(service.putMemory(
                            RequestParameters.optionalText(params, "memoryId", null),
                            RequestParameters.requiredText(params, "workspaceId"),
                            RequestParameters.requiredText(params, "kind"),
                            RequestParameters.requiredText(params, "content"),
                            RequestParameters.optionalLong(params, "expectedRevision", 0),
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.MEMORY_DELETE ->
                    RpcResults.flag(
                            "deleted",
                            service.deleteMemory(
                                    RequestParameters.requiredText(params, "memoryId"),
                                    RequestParameters.optionalLong(params, "expectedRevision", -1),
                                    RequestParameters.optionalText(params, "idempotencyKey", null)));
                case RpcMethods.SKILL_LIST ->
                    json.valueToTree(
                            service.listSkills().stream().map(wire::skill).toList());
                case RpcMethods.SKILL_INSTALL -> installSkill(service, params);
                case RpcMethods.SKILL_ENABLE -> setSkill(service, params, true);
                case RpcMethods.SKILL_DISABLE -> setSkill(service, params, false);
                case RpcMethods.SKILL_UNINSTALL ->
                    RpcResults.flag(
                            "deleted",
                            service.uninstallSkill(
                                    RequestParameters.requiredText(params, "skillId"),
                                    RequestParameters.optionalLong(params, "expectedRevision", -1),
                                    RequestParameters.optionalText(params, "idempotencyKey", null)));
                default -> throw new RpcRouter.MethodNotFound(method);
            };
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("knowledge operation failed", failure);
        }
    }

    private static com.javaclaw.agent.knowledge.LearningRepository.SkillDraft skillDraft(JsonNode params) {
        JsonNode value = params.path("draft");
        JsonNode entries = value.path("sourceItemIds");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > 25) {
            throw new IllegalArgumentException("Skill sourceItemIds must contain 1..25 references");
        }
        var sources = new java.util.ArrayList<String>();
        entries.forEach(entry -> {
            if (!entry.isTextual()) {
                throw new IllegalArgumentException("source Item id must be text");
            }
            sources.add(entry.textValue());
        });
        return new com.javaclaw.agent.knowledge.LearningRepository.SkillDraft(
                RequestParameters.requiredText(value, "workspaceId"),
                RequestParameters.optionalText(value, "targetId", null),
                RequestParameters.requiredText(value, "name"),
                RequestParameters.requiredText(value, "version"),
                RequestParameters.requiredText(value, "manifest"),
                sources,
                RequestParameters.optionalLong(value, "expectedTargetRevision", 0));
    }

    private static com.javaclaw.agent.knowledge.MemoryRepository.MemoryDraft memoryDraft(JsonNode params) {
        JsonNode value = params.path("memory");
        var sources = new java.util.ArrayList<String>();
        JsonNode references = value.path("sourceItemIds");
        if (!references.isMissingNode() && (!references.isArray() || references.size() > 25)) {
            throw new IllegalArgumentException("sourceItemIds must contain at most 25 references");
        }
        references.forEach(reference -> {
            if (!reference.isTextual()) {
                throw new IllegalArgumentException("source Item identifiers must be strings");
            }
            sources.add(reference.textValue());
        });
        return new com.javaclaw.agent.knowledge.MemoryRepository.MemoryDraft(
                RequestParameters.optionalText(value, "id", null),
                RequestParameters.requiredText(value, "workspaceId"),
                RequestParameters.requiredText(value, "kind"),
                RequestParameters.optionalText(value, "subject", ""),
                RequestParameters.optionalText(value, "attribute", ""),
                RequestParameters.requiredText(value, "content"),
                RequestParameters.optionalBoolean(value, "pinned", false),
                sources);
    }

    private JsonNode installSkill(KnowledgeUseCases service, JsonNode params) {
        JsonNode manifest = params == null ? null : params.get("manifest");
        if (manifest == null || manifest.isNull()) {
            throw new IllegalArgumentException("manifest is required");
        }
        String manifestJson = manifest.isTextual() ? manifest.textValue() : manifest.toString();
        return json.valueToTree(wire.skill(service.installSkill(
                RequestParameters.requiredText(params, "skillId"),
                RequestParameters.requiredText(params, "name"),
                RequestParameters.requiredText(params, "version"),
                manifestJson,
                RequestParameters.optionalBoolean(params, "enabled", true),
                RequestParameters.optionalLong(params, "expectedRevision", 0),
                RequestParameters.optionalText(params, "idempotencyKey", null))));
    }

    private JsonNode setSkill(KnowledgeUseCases service, JsonNode params, boolean enabled) {
        return RpcResults.flag(
                "updated",
                service.setSkillEnabled(
                        RequestParameters.requiredText(params, "skillId"),
                        enabled,
                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                        RequestParameters.optionalText(params, "idempotencyKey", null)));
    }

    private KnowledgeUseCases requireService() {
        if (knowledge == null) {
            throw new IllegalStateException("knowledge capability is unavailable");
        }
        return knowledge;
    }
}
